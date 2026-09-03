package dev.cupthread.demo

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.R
import dev.cupthread.feedback.ui.ChangelogOverlay
import dev.cupthread.feedback.ui.CupThreadTheme
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import dev.cupthread.feedback.ui.FeedbackComposer
import dev.cupthread.feedback.ui.RoadmapBoardScreen
import dev.cupthread.feedback.ui.WhatsNewScreen
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Automated UI screenshot and visual regression testing suite for CupThread Android SDK.
 *
 * Runs instrumented Jetpack Compose tests across all key user workflows, capturing screenshots
 * and saving them to the device storage for documentation and visual regression checks.
 */
@RunWith(AndroidJUnit4::class)
class CupThreadDemoScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var client: FeedbackClient
    private lateinit var mockWebServer: MockWebServer
    private val userToken = "demo_test_token_123"

    @Before
    fun setUp() {
        mockWebServer = MockWebServer().apply {
            dispatcher = DemoMockData.dispatcher()
            start()
        }
        client = DemoMockData.createMockClient(mockWebServer.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        if (::mockWebServer.isInitialized) mockWebServer.shutdown()
    }

    @Test
    fun capture01Roadmap() {
        composeTestRule.setContent {
            CupThreadTheme(client) {
                RoadmapBoardScreen(
                    client = client,
                    userToken = userToken,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        awaitText("Interactive Lock & Home Screen Widgets")
        saveScreenshot("roadmap")
    }

    @Test
    fun capture02FeatureRequests() {
        composeTestRule.setContent {
            CupThreadTheme(client) {
                FeatureRequestsScreen(
                    client = client,
                    userToken = userToken,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        awaitText("Interactive Lock & Home Screen Widgets")
        saveScreenshot("feature_requests")
    }

    @Test
    fun capture03WhatsNew() {
        composeTestRule.setContent {
            CupThreadTheme(client) {
                WhatsNewScreen(
                    client = client,
                    userToken = userToken,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        awaitText("Material 3 Design & Speed Improvements")
        saveScreenshot("whats_new")
    }

    @Test
    fun capture04ChangelogOverlay() {
        composeTestRule.setContent {
            CupThreadTheme(client) {
                ChangelogOverlay(
                    client = client,
                    visible = true,
                    onDismiss = {}
                )
            }
        }
        awaitText("What's New in v2.4.0")
        saveScreenshot("changelog_overlay")
    }

    @Test
    fun capture05FeedbackComposer() {
        composeTestRule.setContent {
            CupThreadTheme(client) {
                FeedbackComposer(
                    client = client,
                    userToken = userToken,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        awaitText(R.string.cupthread_feedback_title)
        saveScreenshot("feedback_composer")
    }

    @Test
    fun capture06SubmitRequest() {
        composeTestRule.setContent {
            CupThreadTheme(client) {
                FeatureRequestsScreen(
                    client = client,
                    userToken = userToken,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        awaitText("Interactive Lock & Home Screen Widgets")
        val requestFeature = targetContext.getString(R.string.cupthread_features_request_feature)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithContentDescription(requestFeature).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription(requestFeature).performClick()
        awaitText(R.string.cupthread_features_compose_title)
        saveScreenshot("submit_request")
    }

    private fun saveScreenshot(name: String) {
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            checkNotNull(targetContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)),
            "cupthread_screenshots"
        )
        check(dir.isDirectory || dir.mkdirs()) { "Unable to create screenshot directory: $dir" }
        FileOutputStream(File(dir, "$name.png")).use { out ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Unable to encode $name screenshot" }
        }
    }

    private fun awaitText(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    private fun awaitText(resId: Int) = awaitText(targetContext.getString(resId))

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
}
