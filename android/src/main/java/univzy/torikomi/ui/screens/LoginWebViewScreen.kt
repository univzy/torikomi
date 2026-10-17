package univzy.torikomi.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import univzy.torikomi.R
import univzy.torikomi.engine.CookieStore

private const val TAG = "LoginWebView"

/**
 * In-app WebView so the user can log into any site (Instagram, TikTok, X, …).
 * Closing the screen saves that site's cookies for yt-dlp's `--cookies` to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginWebViewScreen(loginUrl: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val siteName = CookieStore.siteKey(loginUrl) ?: loginUrl
    var progress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // Hold the WebView so back can navigate within the login flow before exiting.
    val webView = remember {
        WebView(context).apply {
            // White, not transparent: a transparent WebView shows the (dark) app theme as a black page.
            setBackgroundColor(Color.WHITE)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION") databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Many sites (TikTok, X, Facebook, Google) serve a blank/"unsupported" page to the
                // "; wv" WebView user agent. Present as plain mobile Chrome instead.
                userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
                // Keep the login page light even when the phone/app is in dark mode.
                if (Build.VERSION.SDK_INT in 29..32) @Suppress("DEPRECATION") forceDark = WebSettings.FORCE_DARK_OFF
                if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // instagram://, intent://, snssdk:// … would end in a blank error page; stay in the WebView.
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        Log.i(TAG, "blocked non-web scheme: ${request.url}")
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    error = null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                    Log.w(TAG, "error ${err.errorCode} ${err.description} for ${request.url}")
                    if (request.isForMainFrame) error = "${err.description} (${err.errorCode})"
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            loadUrl(loginUrl)
        }
    }

    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    val finish = {
        // Save under the login URL and wherever the user ended up (e.g. after a redirect to another host).
        CookieStore.save(context, loginUrl)
        webView.url?.let { if (CookieStore.siteKey(it) == CookieStore.siteKey(loginUrl)) CookieStore.save(context, it) }
        onDone()
    }

    BackHandler { if (webView.canGoBack()) webView.goBack() else finish() }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title, siteName), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = finish) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = finish) { Text(stringResource(R.string.login_done)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

            if (progress in 1..99 && error == null) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }

            error?.let { msg ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.login_load_failed, siteName),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            msg,
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { error = null; webView.reload() }, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.login_retry))
                        }
                    }
                }
            }
        }
    }
}
