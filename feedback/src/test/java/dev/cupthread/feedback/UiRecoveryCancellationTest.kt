package dev.cupthread.feedback

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.FeatureRequestComposeSheetBody
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import dev.cupthread.feedback.ui.SubscribeSheetBody
import dev.cupthread.feedback.ui.UserProfileSheet
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Regression coverage for issue #206: every recovery ladder wrapping a suspend call must
 * rethrow [kotlinx.coroutines.CancellationException] before its generic catch — the invariant
 * #190 established for the vote paths, applied to the four sites it missed.
 *
 * Each test holds its request in flight inside the stub transport, cancels the owning
 * coroutine the way production does (a keyed `LaunchedEffect` restart, or the composition
 * dying under a `rememberCoroutineScope`), then forces the resumption to run and asserts the
 * cancelled coroutine never mutates the recovery state or reports success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiRecoveryCancellationTest {

    @get:Rule
    val compose = createComposeRule()

    private val screenVisible = mutableStateOf(true)
    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())

    /** Opens the gate that the HANG fetch blocks on until its resumption is forced. */
    private val hangGate = CountDownLatch(1)

    /** Opens the gate that [Versions.GATED_V2] fetches block on. */
    private val versionsGate = CountDownLatch(1)

    /**
     * Counts held responses handed back by their transport threads. The cancelled coroutine's
     * resumption is only queued once its transport thread woke up and returned the response,
     * and that wake-up runs on a wall clock the frame pumps cannot bound — so every test
     * awaits the handoff before settling the resumption through the frame clock (the #189
     * flakiness shape: settling frame pumps alone race the blocked transport thread).
     */
    private val handedOff = AtomicInteger()

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        screenVisible.value = true
        synchronized(calls) { calls.clear() }
    }

    /** How the version-filter fetch answers, per mounted client. */
    private enum class Versions {
        /** Answers with the 1.0.0 payload. */
        RESPOND_V1,
        /** Blocks on [hangGate] until the test forces the resumption (the #206 shape). */
        HANG,
        /** Blocks on [versionsGate], then answers with the 2.0.0 payload. */
        GATED_V2
    }

    private fun client(versions: Versions) = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/versions/") ->
                    when (versions) {
                        Versions.RESPOND_V1 -> HttpResponse(200, versionsJson("1.0.0"))
                        Versions.HANG -> {
                            hangGate.await()
                            handedOff.incrementAndGet()
                            HttpResponse(200, versionsJson("1.0.0"))
                        }
                        Versions.GATED_V2 -> {
                            versionsGate.await()
                            handedOff.incrementAndGet()
                            HttpResponse(200, versionsJson("2.0.0"))
                        }
                    }
                request.url.contains("/api/v1/feature-requests") ->
                    HttpResponse(200, PAGE_JSON)
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Test
    fun cancelledVersionsFetchDoesNotClobberRenderedVersions() {
        val currentClient = mutableStateOf(client(Versions.RESPOND_V1))
        compose.setContent {
            if (screenVisible.value) FeatureRequestsScreen(currentClient.value, USER_TOKEN)
        }

        // The 1.0.0 fetch completes: the version filter is usable.
        awaitCondition { versionsCalls() >= 1 }
        awaitCondition { filterUsable() }

        // Swap the client once: the restarted fetch hangs cancellably forever. A hanging
        // fetch is not a failure — the rendered versions must stay untouched.
        compose.runOnIdle { currentClient.value = client(Versions.HANG) }
        awaitCondition { versionsCalls() >= 2 }
        settle()
        assertTrue("a hanging fetch must not disable the filter", filterUsable())

        // Swap again: the hanging fetch is cancelled mid-flight (issue #206). The ladder
        // used to swallow the cancellation into the empty-list fallback, which disabled the
        // filter; with the rethrow the cancelled coroutine leaves the rendered list alone.
        compose.runOnIdle { currentClient.value = client(Versions.GATED_V2) }
        awaitCondition { versionsCalls() >= 3 }
        settle()

        // Release the held response of the already-cancelled fetch so its resumption runs:
        // the client answers 1.0.0 again, but into a cancelled coroutine — it must be
        // delivered as a cancellation, never rewritten into the rendered state.
        hangGate.countDown()
        awaitCondition { handedOff.get() >= 1 }
        assertTrue(
            "the cancelled fetch must not wipe versions a completed fetch rendered",
            assertStable { filterUsable() }
        )

        // The fresh fetch for the new client still completes normally once released.
        versionsGate.countDown()
        awaitCondition { handedOff.get() >= 2 }
        awaitCondition { filterUsable() }
    }

    @Test
    fun cancelledProfileReloadRendersFreshProfileWithoutErrorBleed() {
        val profileGate = CountDownLatch(1)
        val profileClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
            transport = HttpTransport { request ->
                calls += request
                when {
                    request.url.contains("/api/v1/public/config/") ->
                        HttpResponse(200, configJson())
                    request.url.contains("/api/v1/users/$PROFILE_U1/profile") -> {
                        // Hold the superseded fetch in flight until the test releases it,
                        // so its resumption is forced to run after the reload superseded it.
                        profileGate.await()
                        handedOff.incrementAndGet()
                        HttpResponse(200, profileJson("Profile One"))
                    }
                    request.url.contains("/api/v1/users/$PROFILE_U2/profile") ->
                        HttpResponse(200, profileJson("Profile Two"))
                    else -> HttpResponse(404, """{"error":"not_found"}""")
                }
            }
        )
        val userId = mutableStateOf(PROFILE_U1)
        compose.setContent { UserProfileSheet(profileClient, userId.value) {} }

        awaitCondition { profileCalls(PROFILE_U1) >= 1 }

        // Changing the userId cancels the in-flight load mid-request and starts the fresh one.
        compose.runOnIdle { userId.value = PROFILE_U2 }
        awaitCondition { dialogHasText("Profile Two") }
        assertFalse("no load error may surface", dialogHasText("Failed to load profile"))

        // Release the held response: the cancelled load resumes while the fresh profile is
        // showing. It must end as a cancellation — never as a visible load failure.
        profileGate.countDown()
        awaitCondition { handedOff.get() >= 1 }
        settle()
        assertFalse(dialogHasText("Failed to load profile"))
        assertTrue(dialogHasText("Profile Two"))
    }

    @Test
    fun cancelledSubmitIsNotASubmissionAndFreshFormStillWorks() {
        val submitGate = CountDownLatch(1)
        val submitCalls = AtomicInteger()
        val submitClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
            transport = HttpTransport { request ->
                calls += request
                when {
                    request.url.contains("/api/v1/public/config/") ->
                        HttpResponse(200, configJson())
                    request.method == "POST" && request.url.endsWith("/api/v1/feature-requests") -> {
                        // Hold the first submission in flight; retries answer immediately.
                        if (submitCalls.incrementAndGet() == 1) {
                            submitGate.await()
                            handedOff.incrementAndGet()
                        }
                        HttpResponse(201, SUBMITTED_JSON)
                    }
                    else -> HttpResponse(404, """{"error":"not_found"}""")
                }
            }
        )
        var submitted = false
        compose.setContent {
            if (screenVisible.value) {
                FeatureRequestComposeSheetBody(
                    client = submitClient,
                    userToken = USER_TOKEN,
                    onSubmitted = { submitted = true },
                    // The production sheet host applies no scroll, but the Robolectric
                    // viewport is smaller than the form; the wrapper keeps the submit
                    // button reachable for the injected taps.
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                )
            }
        }
        fillForm()
        clickSubmit()
        awaitCondition { submitCalls.get() >= 1 }

        // Taking the form out of composition cancels the sheet-scoped submission mid-flight.
        compose.runOnIdle { screenVisible.value = false }
        settle()

        // Release the held 201: the resumption runs against a dead composition. The
        // cancelled submission must end as a cancellation — it never reports success even
        // though the transport answered.
        submitGate.countDown()
        awaitCondition { handedOff.get() >= 1 }
        settle()
        assertFalse("a cancelled submission is not a submission", submitted)

        // A fresh mount starts clean and completes a normal submission end to end.
        compose.runOnIdle { screenVisible.value = true }
        fillForm()
        clickSubmit()
        awaitCondition { submitted && submitCalls.get() >= 2 }
    }

    @Test
    fun cancelledSubscribeIsNotASubscriptionAndFreshSheetStillWorks() {
        val subscribeGate = CountDownLatch(1)
        val subscribeCalls = AtomicInteger()
        val subscribeClient = FeedbackClient(
            config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
            transport = HttpTransport { request ->
                calls += request
                when {
                    request.url.contains("/api/v1/public/config/") ->
                        HttpResponse(200, configJson())
                    request.method == "POST" && request.url.contains("/changelog/subscribe") -> {
                        // Hold the first subscribe in flight; retries answer immediately.
                        if (subscribeCalls.incrementAndGet() == 1) {
                            subscribeGate.await()
                            handedOff.incrementAndGet()
                        }
                        HttpResponse(200, """{"subscribed":true}""")
                    }
                    else -> HttpResponse(404, """{"error":"not_found"}""")
                }
            }
        )
        var dismissed = false
        compose.setContent {
            if (screenVisible.value) {
                SubscribeSheetBody(
                    client = subscribeClient,
                    userToken = USER_TOKEN,
                    onDismiss = { dismissed = true }
                )
            }
        }
        emailField().performTextInput("subscriber@example.com")
        compose.onNodeWithText("Subscribe").performClick()
        awaitCondition { subscribeCalls.get() >= 1 }

        // Removing the body from composition cancels the sheet-scoped subscribe mid-flight;
        // releasing the gate then forces its resumption against the dead composition.
        compose.runOnIdle { screenVisible.value = false }
        settle()
        subscribeGate.countDown()
        awaitCondition { handedOff.get() >= 1 }
        settle()

        // The cancelled subscribe must not confirm the phase change or dismiss the sheet.
        compose.onNodeWithText("Check Your Inbox").assertDoesNotExist()
        assertFalse(dismissed)

        // A fresh mount starts clean and subscribes normally end to end.
        compose.runOnIdle { screenVisible.value = true }
        emailField().performTextInput("subscriber@example.com")
        compose.onNodeWithText("Subscribe").performClick()
        awaitCondition { subscribeCalls.get() >= 2 }
        compose.onNodeWithText("Check Your Inbox").assertExists()
    }

    // -- harness ---------------------------------------------------------------

    private fun versionsCalls(): Int = synchronized(calls) {
        calls.count { it.url.contains("/api/v1/public/versions/") }
    }

    private fun profileCalls(userId: String): Int = synchronized(calls) {
        calls.count { it.url.contains("/api/v1/users/$userId/profile") }
    }

    private fun fillForm() {
        // Field order in FeatureRequestComposeSheetBody: title, description, name.
        compose.onAllNodes(isEditable())[0].performTextInput("Scheduled posts")
        compose.onAllNodes(isEditable())[1]
            .performTextInput("Let authors queue a request for a future date")
        compose.waitForIdle()
    }

    private fun emailField() = compose.onNode(isEditable())

    private fun clickSubmit() {
        // The submit button may sit below the fold in the small Robolectric window; scroll
        // it into view so the injected tap lands on real layout bounds.
        runCatching {
            compose.onNode(hasScrollAction()).performScrollToNode(
                hasText("Submit") and hasClickAction()
            )
        }
        compose.waitForIdle()
        compose.onNode(hasText("Submit") and hasClickAction()).performClick()
    }

    /** Whether the version-filter action is present and enabled (bound to versions.isNotEmpty()). */
    private fun filterUsable(): Boolean = runCatching {
        compose.onNodeWithContentDescription("Filter by version").assertIsEnabled()
    }.isSuccess

    /**
     * Pumps frames until [observation] stops changing across three consecutive pumps and
     * returns its final value. Used right after a handoff: the resumed coroutine's state
     * writes are frame-tick deterministic once queued, so a stable observation means every
     * pending resumption has been applied — reading earlier could race a pending write.
     */
    private fun assertStable(observation: () -> Boolean): Boolean {
        var last = observation()
        var stable = 0
        repeat(STABILITY_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
            val now = observation()
            if (now == last) stable++ else stable = 1
            last = now
        }
        return last
    }

    /** Pumps the settled frame count so cancelled-coroutine resumptions have run. */
    private fun settle() {
        repeat(SETTLE_PUMPS) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration.
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; calls=" +
                    synchronized(calls) { calls.joinToString { it.method + " " + it.url } },
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    /** Texts currently visible inside the top dialog window (sheets render outside the rule). */
    private fun dialogTexts(): Set<String> {
        val dialog = ShadowDialog.getLatestDialog() ?: return emptySet()
        if (!dialog.isShowing) return emptySet()
        val texts = mutableSetOf<String>()
        dialog.window?.decorView?.let { collectDialogTexts(it, texts) }
        return texts
    }

    private fun dialogHasText(text: String) = text in dialogTexts()

    private fun collectDialogTexts(view: View, into: MutableSet<String>) {
        semanticsOwnerOf(view)?.rootSemanticsNode?.let { root ->
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.toString() }
                node.children.forEach { walk(it) }
            }
            walk(root)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectDialogTexts(view.getChildAt(i), into)
        }
    }

    /**
     * The compose semantics owner of [view]'s view subtree, if any.
     *
     * The view hosting the dialog's composition exposes its `getSemanticsOwner()` accessor
     * only reflectively, so it is resolved as such.
     */
    private fun semanticsOwnerOf(view: View): SemanticsOwner? = runCatching {
        view.javaClass.methods
            .firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?.invoke(view) as? SemanticsOwner
    }.getOrNull()

    private fun versionsJson(label: String): String = """
        {
          "versions": [
            {
              "id": "v-$label",
              "appId": "app-1",
              "label": "$label",
              "position": 1,
              "released": true,
              "releasedAt": "2026-01-01T00:00:00Z",
              "description": null,
              "createdAt": "2026-01-01T00:00:00Z",
              "updatedAt": "2026-01-01T00:00:00Z"
            }
          ]
        }
    """.trimIndent()

    private fun profileJson(displayName: String): String = """
        {
          "profile": {
            "clerkUserId": "clerk_1",
            "displayName": "$displayName",
            "createdAt": "2026-01-01T00:00:00Z"
          },
          "publicApps": [],
          "recentComments": []
        }
    """.trimIndent()

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$APP_KEY",
          "slug": "ui-recovery-cancellation",
          "name": "UI Recovery Cancellation",
          "sdk": {
            "features": {
              "feedback": true,
              "featureRequests": true,
              "roadmap": true,
              "changelog": true
            }
          }
        }
    """.trimIndent()

    private companion object {
        const val APP_KEY = "app_uirecovery1234"
        const val USER_TOKEN = "token-uirecovery-0000"
        const val PROFILE_U1 = "u_cancel_profile_one"
        const val PROFILE_U2 = "u_cancel_profile_two"
        const val PAGE_JSON = """{"requests":[],"total":0,"hasMore":false,"nextCursor":null}"""
        const val SUBMITTED_JSON = """{"featureRequestId":"fr_cancel_1","pending":false}"""
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
        const val SETTLE_PUMPS = 8
        const val STABILITY_PUMPS = 24
    }
}
