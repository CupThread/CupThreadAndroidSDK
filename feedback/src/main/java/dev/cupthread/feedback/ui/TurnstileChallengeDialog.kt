package dev.cupthread.feedback.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cupthread.feedback.AndroidTurnstileBridge
import dev.cupthread.feedback.TurnstileBridgeMessage
import dev.cupthread.feedback.TurnstileChallenge
import dev.cupthread.feedback.TurnstileHostedChallenge

/**
 * An in-app modal challenge dialog that renders the hosted Cloudflare Turnstile challenge page
 * in a secured [WebView] to mint a single-use verification token.
 *
 * Implements the cross-SDK hosted challenge contract (issue #71 / SaaS #1086):
 * - Loads `https://app.cupthread.com/turnstile/verify` with query parameters.
 * - Injects [AndroidTurnstileBridge] under `window.AndroidTurnstileBridge`.
 * - Forwards `{status: "success", token}` to [onToken] and dismisses the dialog.
 * - Forwards `{status: "error", code}` to [onError].
 * - Automatically reloads the challenge on `{status: "expired"}`.
 *
 * Example:
 * ```kotlin
 * if (showChallenge) {
 *     TurnstileChallengeDialog(
 *         siteKey = appConfig.turnstile?.siteKey ?: return,
 *         challenge = TurnstileChallenge.feedback(appConfig.appKey),
 *         onToken = { token ->
 *             showChallenge = false
 *             submitWithToken(token)
 *         },
 *         onDismiss = { showChallenge = false }
 *     )
 * }
 * ```
 *
 * @param siteKey The public Turnstile widget site key from [dev.cupthread.feedback.PublicAppConfig.turnstile].
 * @param challenge The [TurnstileChallenge] representing the target endpoint binding.
 * @param onToken Callback invoked with the freshly minted Turnstile token.
 * @param onError Optional callback invoked when the widget reports a client-side error code (e.g. `"110200"`).
 * @param onDismiss Callback invoked when the user closes or dismisses the dialog.
 * @param portalBaseUrl Portal origin; defaults to [TurnstileHostedChallenge.DEFAULT_PORTAL_BASE_URL].
 * @param theme Optional widget color theme: `"light"`, `"dark"`, or `"auto"`.
 * @param locale Optional widget locale tag (e.g. `"en"`, `"zh-CN"`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileChallengeDialog(
    siteKey: String,
    challenge: TurnstileChallenge,
    onToken: (String) -> Unit,
    onError: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit,
    portalBaseUrl: String = TurnstileHostedChallenge.DEFAULT_PORTAL_BASE_URL,
    theme: String? = null,
    locale: String? = null
) {
    var isLoading by remember { mutableStateOf(true) }
    val currentOnToken by rememberUpdatedState(onToken)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentPortalBaseUrl by rememberUpdatedState(portalBaseUrl)

    val challengeUrl = remember(siteKey, challenge, theme, locale, portalBaseUrl) {
        TurnstileHostedChallenge.buildChallengeUrl(
            siteKey = siteKey,
            challenge = challenge,
            theme = theme,
            locale = locale,
            portalBaseUrl = portalBaseUrl
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(340.dp)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)

                            val bridge = AndroidTurnstileBridge { msg ->
                                post {
                                    when (msg) {
                                        is TurnstileBridgeMessage.Success -> {
                                            currentOnToken(msg.token)
                                            currentOnDismiss()
                                        }
                                        is TurnstileBridgeMessage.Error -> {
                                            currentOnError?.invoke(msg.code)
                                        }
                                        is TurnstileBridgeMessage.Expired -> {
                                            reload()
                                        }
                                    }
                                }
                            }
                            addJavascriptInterface(bridge, AndroidTurnstileBridge.BRIDGE_NAME)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    val base = currentPortalBaseUrl
                                    return !url.startsWith(base) && !url.contains("cloudflare.com")
                                }
                            }

                            loadUrl(challengeUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url != challengeUrl) {
                            webView.loadUrl(challengeUrl)
                        }
                    }
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
