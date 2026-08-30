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
