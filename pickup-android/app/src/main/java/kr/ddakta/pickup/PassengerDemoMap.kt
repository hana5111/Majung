package kr.ddakta.pickup

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

private const val MapPage = "https://appassets.androidplatform.net/assets/passenger-map/index.html"

/** Locally bundled map renderer; only public OSM map tiles are loaded remotely. No location access. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PassengerDemoMap(scene: PassengerDemoScene.Scene, passengerName: String, accepted: Boolean, nearby: Boolean, height: Dp = 285.dp) {
    val latestScene by rememberUpdatedState(scene.json(accepted, nearby, passengerName))
    key(scene, passengerName) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(22.dp)),
        factory = { context ->
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply {
                contentDescription = "약속 장소 주변 실제 지도. 차량·탑승자 위치는 시연용입니다."
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "$userAgentString MajungPickupPrototype/0.2.1 (kr.ddakta.pickup)"
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (BuildConfig.DEBUG) Log.d("MajungDemoMap", "${message.messageLevel()} ${message.lineNumber()}: ${message.message()}")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        assets.shouldInterceptRequest(uri)?.let { return it }
                        if (uri.scheme == "https" && uri.host == "tile.openstreetmap.org" &&
                            Regex("^/\\d{1,2}/\\d+/\\d+\\.png$").matches(uri.path.orEmpty())) return null
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.toString() == MapPage) return false
                        if (request.isForMainFrame && request.hasGesture() &&
                            request.url.toString() == "https://www.openstreetmap.org/copyright")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
                        return true
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (url == MapPage) view.evaluateJavascript("window.setPickupState && window.setPickupState($latestScene)") {
                            if (BuildConfig.DEBUG) Log.d("MajungDemoMap", "Map page ready; scene applied: $it")
                        }
                    }
                }
                loadUrl(MapPage)
            }
        },
        update = { it.evaluateJavascript("window.setPickupState && window.setPickupState($latestScene)", null) },
        onRelease = { it.stopLoading(); it.destroy() }
    )
    }
}
