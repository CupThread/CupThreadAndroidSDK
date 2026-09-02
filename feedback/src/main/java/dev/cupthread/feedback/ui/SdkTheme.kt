package dev.cupthread.feedback.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.cupthread.feedback.FeedbackClient
import dev.cupthread.feedback.SdkAppearance
import dev.cupthread.feedback.SdkFeature
import dev.cupthread.feedback.SdkTheme

internal val LocalSdkAppearance = staticCompositionLocalOf<SdkAppearance?> { null }

private data class ThemeSpec(
    val appearance: ForcedAppearance,
    val accent: Long,
    val background: Long,
    val backgroundDark: Long
)

private enum class ForcedAppearance { SYSTEM, LIGHT, DARK }

private fun SdkTheme.spec(): ThemeSpec = when (this) {
    SdkTheme.SYSTEM -> ThemeSpec(ForcedAppearance.SYSTEM, 0xFF2563EB, 0xFFF8FAFC, 0xFF0F172A)
    SdkTheme.LIGHT -> ThemeSpec(ForcedAppearance.LIGHT, 0xFF2563EB, 0xFFFFFFFF, 0xFFFFFFFF)
    SdkTheme.DARK -> ThemeSpec(ForcedAppearance.DARK, 0xFF60A5FA, 0xFF0F172A, 0xFF0F172A)
    SdkTheme.MIDNIGHT -> ThemeSpec(ForcedAppearance.DARK, 0xFF818CF8, 0xFF09090B, 0xFF09090B)
    SdkTheme.OCEAN -> ThemeSpec(ForcedAppearance.SYSTEM, 0xFF0D9488, 0xFFF0FDFA, 0xFF042F2E)
    SdkTheme.FOREST -> ThemeSpec(ForcedAppearance.SYSTEM, 0xFF16A34A, 0xFFF0FDF4, 0xFF052E16)
    SdkTheme.SUNSET -> ThemeSpec(ForcedAppearance.SYSTEM, 0xFFEA580C, 0xFFFFF7ED, 0xFF431407)
    SdkTheme.CANDY -> ThemeSpec(ForcedAppearance.SYSTEM, 0xFFDB2777, 0xFFFDF2F8, 0xFF500724)
}

@Composable
internal fun sdkColorScheme(theme: SdkTheme, systemDark: Boolean) =
    theme.spec().let { spec ->
        val dark = when (spec.appearance) {
            ForcedAppearance.LIGHT -> false
            ForcedAppearance.DARK -> true
            ForcedAppearance.SYSTEM -> systemDark
        }
        val primary = Color(spec.accent)
        val background = Color(if (dark) spec.backgroundDark else spec.background)
        if (dark) {
            darkColorScheme(primary = primary, secondary = primary, tertiary = primary, background = background, surface = background)
        } else {
            lightColorScheme(primary = primary, secondary = primary, tertiary = primary, background = background, surface = background)
        }
    }

/**
 * Remembers and observes the active remote [SdkAppearance] configuration for [client].
 *
 * If an enclosing [SdkSurface] or [CupThreadTheme] has already provided an [SdkAppearance]
 * via `LocalSdkAppearance`, that instance is immediately returned without performing a redundant
 * network call. Otherwise, this composable executes an asynchronous query against
 * [FeedbackClient.fetchAppConfig] inside a [LaunchedEffect], gracefully falling back to
 * [SdkAppearance.defaults] if the request fails or the device is offline.
 *
 * Use this composable when building custom UI flows that need to conditionally render
 * components based on remote console settings:
 *
 * ```kotlin
 * @Composable
 * fun SettingsScreen(client: FeedbackClient, userToken: String) {
 *     val appearance = rememberSdkAppearance(client)
 *
 *     Column {
 *         Text("Settings", style = MaterialTheme.typography.titleLarge)
 *
 *         if (appearance.features.isEnabled(SdkFeature.FEEDBACK)) {
 *             Button(onClick = { /* navigate to feedback composer */ }) {
 *                 Text("Send Feedback")
 *             }
 *         }
 *
 *         if (appearance.features.isEnabled(SdkFeature.CHANGELOG)) {
 *             Button(onClick = { /* open changelog */ }) {
 *                 Text(appearance.changelogOverlay.title)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param client The [FeedbackClient] used to retrieve remote app appearance and features.
 * @return The resolved [SdkAppearance] state.
 */
@Composable
fun rememberSdkAppearance(client: FeedbackClient): SdkAppearance {
    val injected = LocalSdkAppearance.current
    var fetched by remember(client) { mutableStateOf(injected ?: SdkAppearance.defaults) }
    LaunchedEffect(client, injected) {
        if (injected == null) {
            fetched = runCatching { client.fetchAppConfig().sdk }.getOrDefault(SdkAppearance.defaults)
        }
    }
    return injected ?: fetched
}

/**
 * Material 3 Theme wrapper that applies the console-configured [SdkTheme] palette to [content].
 *
 * Automatically resolves the active [SdkAppearance] via [rememberSdkAppearance] and injects it
 * into [LocalSdkAppearance] for descendant composables. Translates the remote [SdkTheme] setting
 * (such as [SdkTheme.OCEAN], [SdkTheme.SUNSET], or [SdkTheme.MIDNIGHT]) into an Android Material 3
 * [androidx.compose.material3.ColorScheme] that adapts to system dark mode preferences.
 *
 * Use [CupThreadTheme] when embedding standalone SDK composables (like [ChangelogOverlay]
 * or custom feedback views) in an existing Jetpack Compose hierarchy:
 *
 * ```kotlin
 * class FeedbackActivity : ComponentActivity() {
 *     private val client = FeedbackClient(FeedbackClientConfig("https://api.cupthread.com", "app_live_xyz"))
 *     private val userTokenStore by lazy { UserTokenStore.create(this) }
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         setContent {
 *             CupThreadTheme(client) {
 *                 FeedbackComposer(
 *                     client = client,
 *                     userToken = userTokenStore.token,
 *                     onSubmit = { finish() }
 *                 )
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param client Shared [FeedbackClient] instance used to fetch remote appearance and theme settings.
 * @param content Composable content to be styled with the resolved theme.
 */
@Composable
fun CupThreadTheme(
    client: FeedbackClient,
    content: @Composable () -> Unit
) {
    val appearance = rememberSdkAppearance(client)
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSdkAppearance provides appearance) {
        MaterialTheme(colorScheme = sdkColorScheme(appearance.theme, dark), content = content)
    }
}

/**
 * Remote feature gate and theming wrapper for SDK UI surfaces.
 *
 * Wraps [content] inside a console-styled [MaterialTheme] and checks whether [feature]
 * is enabled in [SdkAppearance.features]. If enabled, [content] is rendered normally.
 * If the feature has been disabled remotely from the CupThread console, a styled placeholder
 * empty state is rendered informing the user that the surface is currently unavailable.
 *
 * All ready-made SDK screens ([RoadmapBoardScreen], [FeatureRequestsScreen], [WhatsNewScreen],
 * and [FeedbackComposer]) use [SdkSurface] internally. You can also use [SdkSurface] to guard
 * your own custom UI modules:
 *
 * ```kotlin
 * @Composable
 * fun CustomRoadmapTab(client: FeedbackClient, userToken: String) {
 *     SdkSurface(client = client, feature = SdkFeature.ROADMAP) {
 *         // This block only executes if ROADMAP is enabled in the CupThread console.
 *         MyCustomRoadmapKanbanView(client = client, userToken = userToken)
 *     }
 * }
 * ```
 *
 * @param client Shared [FeedbackClient] instance used to check remote feature flags and appearance.
 * @param feature The target [SdkFeature] required to display [content].
 * @param content The surface composable content rendered when [feature] is enabled.
 */
@Composable
fun SdkSurface(
    client: FeedbackClient,
    feature: SdkFeature,
    content: @Composable () -> Unit
) {
    val appearance = rememberSdkAppearance(client)
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSdkAppearance provides appearance) {
        MaterialTheme(colorScheme = sdkColorScheme(appearance.theme, dark)) {
            if (appearance.features.isEnabled(feature)) {
                content()
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = when (feature) {
                            SdkFeature.FEEDBACK -> "Feedback Unavailable"
                            SdkFeature.FEATURE_REQUESTS -> "Requests Unavailable"
                            SdkFeature.ROADMAP -> "Roadmap Unavailable"
                            SdkFeature.CHANGELOG -> "Updates Unavailable"
                        },
                        body = "This surface is turned off in the CupThread console."
                    )
                }
            }
        }
    }
}
