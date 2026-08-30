package dev.cupthread.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cupthread.demo.BuildConfig
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.FeedbackClientConfig
import dev.cupthread.feedback.SdkAppearance
import dev.cupthread.feedback.UserTokenStore
import dev.cupthread.feedback.ui.CupThreadTheme
import dev.cupthread.feedback.ui.ChangelogOverlay
import dev.cupthread.feedback.ui.FeatureRequestsScreen
import dev.cupthread.feedback.ui.FeedbackComposer
import dev.cupthread.feedback.ui.RoadmapBoardScreen
import dev.cupthread.feedback.ui.WhatsNewScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val client = FeedbackClient(
            FeedbackClientConfig(
                baseUrl = BuildConfig.CUPTHREAD_BASE_URL,
                appKey = BuildConfig.CUPTHREAD_APP_KEY
            )
        )
        val userToken = UserTokenStore.create(this).token

        setContent {
            CupThreadTheme(client) {
                var tab by remember { mutableIntStateOf(0) }
                var appearance by remember { mutableStateOf(SdkAppearance.defaults) }
                var showOverlay by remember { mutableStateOf(false) }
                LaunchedEffect(client) {
                    appearance = runCatching { client.fetchAppConfig().sdk }.getOrDefault(SdkAppearance.defaults)
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            if (appearance.features.roadmap) {
                                NavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    icon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                                    label = { Text("Roadmap") }
                                )
                            }
                            if (appearance.features.changelog) {
                                NavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    label = { Text("What's New") }
                                )
                            }
                            if (appearance.features.featureRequests) {
                                NavigationBarItem(
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                                    label = { Text("Requests") }
                                )
                            }
                            if (appearance.features.feedback) {
                                NavigationBarItem(
                                    selected = tab == 3,
                                    onClick = { tab = 3 },
                                    icon = { Icon(Icons.Outlined.MailOutline, contentDescription = null) },
                                    label = { Text("Feedback") }
                                )
                            }
                        }
                    }
                ) { padding ->
                    val screenModifier = Modifier.padding(padding)
                    when (tab) {
                        0 -> RoadmapBoardScreen(client, userToken, screenModifier)
                        1 -> androidx.compose.foundation.layout.Column(screenModifier) {
                            TextButton(onClick = { showOverlay = true }) { Text("Show latest overlay") }
                            WhatsNewScreen(client, userToken)
                        }
                        2 -> FeatureRequestsScreen(client, userToken, screenModifier)
                        else -> FeedbackComposer(client, userToken, modifier = screenModifier)
                    }
                }
                ChangelogOverlay(client, visible = showOverlay, onDismiss = { showOverlay = false })
            }
        }
    }
}
