package dev.cupthread.feedback

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.ChangelogOverlay
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Regression coverage for issue #165: the [ChangelogOverlay] composable is designed to be mounted
 * unconditionally and toggled via [ChangelogOverlay.visible], so a failed load attempt must never
 * poison later presentations. The sheet gives the error branch priority over the entry list, which
 * means an `error` value left over from a previous attempt keeps the failure state on screen even
 * after a later attempt succeeds — so every fresh attempt must reset the load state first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangelogOverlayRecoveryTest {

    @get:Rule
    val compose = createComposeRule()

    private val appKey = "app_overlaytest123"
    private val visible = mutableStateOf(false)
    private val onlyIfUnseen = mutableStateOf(false)

    /** `false` makes the changelog endpoint return 500 so the overlay's load attempt fails. */
    private val changelogOk = AtomicReference(true)

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = appKey),
        transport = HttpTransport { request ->
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/apps/$appKey/changelog") ->
                    if (changelogOk.get()) HttpResponse(200, changelogJson())
                    else HttpResponse(500, """{"error":"internal_error"}""")
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    @After
    fun resetState() {
        SdkAppearanceCache.clear()
        changelogOk.set(true)
        visible.value = false
        onlyIfUnseen.value = false
    }

    @Test
    fun overlayRecoversAfterTransientFailureOnReShow() {
        changelogOk.set(false)
        compose.setContent {
            ChangelogOverlay(
                client = client,
                visible = visible.value,
                onDismiss = {}
            )
        }

        compose.runOnIdle { visible.value = true }
        awaitCondition { ERROR_TITLE in visibleTexts() }

        compose.runOnIdle {
            changelogOk.set(true)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }

        awaitCondition { ENTRY_TITLE in visibleTexts() && ERROR_TITLE !in visibleTexts() }
    }

    @Test
    fun overlayErrorClearsWhenEffectRestartsWhileMounted() {
        changelogOk.set(false)
        compose.setContent {
            ChangelogOverlay(
                client = client,
                visible = true,
                onlyIfUnseen = onlyIfUnseen.value,
                onDismiss = {}
            )
        }

        awaitCondition { ERROR_TITLE in visibleTexts() }

        // Restart the load effect without dropping the composable from the composition: the
        // succeeding attempt must replace the stale error with the fetched entries.
        compose.runOnIdle {
            changelogOk.set(true)
            onlyIfUnseen.value = true
        }

        awaitCondition { ENTRY_TITLE in visibleTexts() && ERROR_TITLE !in visibleTexts() }
    }

    @Test
    fun overlayShowsErrorForCurrentFailedAttemptAfterSuccess() {
        compose.setContent {
            ChangelogOverlay(
                client = client,
                visible = true,
                onlyIfUnseen = onlyIfUnseen.value,
                onDismiss = {}
            )
        }

        awaitCondition { ENTRY_TITLE in visibleTexts() }

        compose.runOnIdle {
            changelogOk.set(false)
            onlyIfUnseen.value = true
        }

        awaitCondition { ERROR_TITLE in visibleTexts() && ENTRY_TITLE !in visibleTexts() }
    }

    /**
     * All text currently visible across the test's semantics roots.
     *
     * [ChangelogOverlaySheet] renders inside a Material 3 [androidx.compose.material3.ModalBottomSheet],
     * which hosts its content in a separate dialog window that the compose test rule's `onNode*`
     * matchers do not reach, so the dialog's semantics owner is scanned explicitly. Dismissed
     * dialogs are skipped (`isShowing`) so stale trees cannot satisfy the absence assertions.
     */
    private fun visibleTexts(): Set<String> {
        val texts = mutableSetOf<String>()
        val roots = mutableListOf<SemanticsNode>()
        runCatching {
            roots += compose.onRoot().fetchSemanticsNode()
        }
        val dialog = ShadowDialog.getLatestDialog()
        if (dialog != null && dialog.isShowing) {
            dialog.window?.decorView?.let { collectSemanticsRoots(it, roots) }
        }
        roots.forEach { root -> collectTexts(root, texts) }
        return texts
    }

    /**
     * Semantics roots of any compose view attached under [view].
     *
     * The compose view that hosts sheet content (the internal `AndroidComposeView`) exposes its
     * `getSemanticsOwner()` accessor only reflectively, so it is resolved as such.
     */
    private fun collectSemanticsRoots(view: View, into: MutableList<SemanticsNode>) {
        runCatching {
            val owner = view.javaClass.methods
                .firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
                ?.invoke(view) as? SemanticsOwner
            owner?.rootSemanticsNode?.let { into += it }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectSemanticsRoots(view.getChildAt(i), into)
            }
        }
    }

    private fun collectTexts(node: SemanticsNode, into: MutableSet<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text }
        node.children.forEach { collectTexts(it, into) }
    }

    /**
     * Pumps the Robolectric main looper and the Compose frame clock until [condition] holds.
     *
     * [androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntil] alone does not resume
     * composition-scoped coroutines under Robolectric, so the frame clock is advanced and the
     * looper idled explicitly on every iteration (same approach as [UserTokenRotationTest]).
     */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; visible texts=" + visibleTexts(),
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$appKey",
          "slug": "overlay-recovery",
          "name": "Overlay Recovery",
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

    private fun changelogJson(): String = """
        {
          "entries": [
            {
              "id": "entry-1",
              "title": "$ENTRY_TITLE",
              "body": "Bug fixes and improvements",
              "versionLabel": "1.2.0",
              "publishedAt": "2026-01-01T00:00:00Z",
              "linkedRequests": []
            }
          ],
          "hasMore": false,
          "nextCursor": null
        }
    """.trimIndent()

    private companion object {
        const val ENTRY_TITLE = "Release 1.2.0"
        const val ERROR_TITLE = "Couldn't load"
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
    }
}
