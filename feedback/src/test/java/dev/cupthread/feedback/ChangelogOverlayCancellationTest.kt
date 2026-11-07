package dev.cupthread.feedback

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cupthread.feedback.internal.HttpRequest
import dev.cupthread.feedback.internal.HttpResponse
import dev.cupthread.feedback.internal.HttpTransport
import dev.cupthread.feedback.internal.SdkAppearanceCache
import dev.cupthread.feedback.ui.presentLatestChangelog
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Regression coverage for issue #177: [presentLatestChangelog] suspends on user dismissal
 * inside a `suspendCancellableCoroutine` that only the dismissal paths resumed, so a caller
 * cancelled while the overlay was showing (a `lifecycleScope` ending with the activity —
 * finish, rotation, split-screen resize) left the dialog attached to the destroyed activity
 * (`WindowLeaked`) with nobody to dismiss it.
 *
 * The suspension now dismisses the window when the caller is cancelled, and the UI-thread
 * setup block refuses to attach a window for a caller that is already gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangelogOverlayCancellationTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = Collections.synchronizedList(mutableListOf<HttpRequest>())

    private val client = FeedbackClient(
        config = FeedbackClientConfig(baseUrl = "https://api.example.com", appKey = APP_KEY),
        transport = HttpTransport { request ->
            calls += request
            when {
                request.url.contains("/api/v1/public/config/") ->
                    HttpResponse(200, configJson())
                request.url.contains("/api/v1/public/apps/$APP_KEY/changelog") ->
                    HttpResponse(200, changelogJson())
                else -> HttpResponse(404, """{"error":"not_found"}""")
            }
        }
    )

    @Before
    fun resetState() {
        SdkAppearanceCache.clear()
        synchronized(calls) { calls.clear() }
    }

    @Test
    fun cancellingTheCallerDismissesTheOverlayDialog() {
        val activity = launchedActivity()
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        val outcome = AtomicReference<Throwable?>(null)
        scope.launch {
            try {
                client.presentLatestChangelog(activity)
            } catch (t: Throwable) {
                outcome.set(t)
            }
        }

        awaitDialogShowing()

        // The documented integration is lifecycleScope.launch { presentLatestChangelog(...) }
        // — it dies with the activity while the sheet is on screen.
        scope.cancel()
        awaitCondition { ShadowDialog.getLatestDialog()?.isShowing != true }

        // The suspension must end with the cancellation, and the window must be released
        // instead of leaking over the destroyed activity.
        awaitCondition { outcome.get() != null }
        assertTrue(
            "expected CancellationException, got ${outcome.get()}",
            outcome.get() is CancellationException
        )
    }

    @Test
    fun userDismissalStillResumesExactlyOnceWithTrue() {
        val activity = launchedActivity()
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        val outcome = AtomicReference<Result<Boolean>?>(null)
        scope.launch {
            try {
                outcome.set(Result.success(client.presentLatestChangelog(activity)))
            } catch (t: Throwable) {
                outcome.set(Result.failure(t))
            }
        }

        awaitDialogShowing()

        // The sheet's primary button dismisses through BOTH resume paths (the button's
        // onDismiss and the dialog's dismiss listener), so a successful single `true`
        // proves the #150 double-resume guard still holds after the cancellation change.
        awaitCondition { dialogHasText(PRIMARY_BUTTON) }
        tapDialogText(PRIMARY_BUTTON)
        awaitCondition { outcome.get() != null }

        assertEquals(true, outcome.get()!!.getOrNull())
        awaitCondition { ShadowDialog.getLatestDialog()?.isShowing != true }
    }

    /** A resumed ComponentActivity to host the overlay dialog (Robolectric-managed). */
    private fun launchedActivity() =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** Pumps the Robolectric main looper and the Compose frame clock until [condition] holds. */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (!condition()) {
            assertTrue(
                "timed out after ${WAIT_TIMEOUT_MS}ms; " +
                    "calls=${synchronized(calls) { calls.joinToString { it.method + " " + it.url } }}",
                System.nanoTime() < deadline
            )
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
    }

    private fun awaitDialogShowing() = awaitCondition {
        ShadowDialog.getLatestDialog()?.isShowing == true
    }

    private fun dialogHasText(text: String): Boolean {
        val decor = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            ?: return false
        val (_, owner) = dialogSemanticsRoot(decor) ?: return false
        val root = owner.rootSemanticsNode ?: return false
        var found = false
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.contains(text) } == true) {
                found = true
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        return found
    }

    /**
     * Taps the dialog node whose [SemanticsProperties.Text] contains [text] by injecting a
     * touch pair into the dialog window: the overlay dialog lives outside the compose test
     * rule's reachable tree, so interactions inside it are dispatched on its decor view.
     */
    private fun tapDialogText(text: String) {
        val dialog = ShadowDialog.getLatestDialog()
        val decor = dialog?.window?.decorView
            ?: throw AssertionError("no dialog showing; cannot tap $text")
        val (composeView, owner) = dialogSemanticsRoot(decor)
            ?: throw AssertionError("no compose semantics inside the dialog")
        val root = owner.rootSemanticsNode
            ?: throw AssertionError("empty semantics tree inside the dialog")
        val matches = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.contains(text) } == true) {
                matches += node
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        val target = matches.lastOrNull()
            ?: throw AssertionError("$text not found in the dialog tree")
        val location = IntArray(2)
        composeView.getLocationInWindow(location)
        val bounds = target.boundsInRoot
        val x = (location[0] + bounds.left + bounds.width / 2).toFloat()
        val y = (location[1] + bounds.top + bounds.height / 2).toFloat()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        decor.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        decor.dispatchTouchEvent(up)
        up.recycle()
    }

    /** First view under [view] that owns a compose semantics tree, with that tree's owner. */
    private fun dialogSemanticsRoot(view: View): Pair<View, SemanticsOwner>? {
        semanticsOwnerOf(view)?.let { return view to it }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dialogSemanticsRoot(view.getChildAt(i))?.let { return it }
            }
        }
        return null
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

    private fun configJson(): String = """
        {
          "appId": "app-1",
          "appKey": "$APP_KEY",
          "slug": "overlay-cancellation",
          "name": "Overlay Cancellation",
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
              "title": "Release 1.2.0",
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
        const val APP_KEY = "app_overlaycancel1234"
        const val PRIMARY_BUTTON = "Continue"
        const val WAIT_TIMEOUT_MS = 10_000L
        const val FRAME_MS = 16L
    }
}
