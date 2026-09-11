package com.example.arviewersync.viewer

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen WebView hosting the Three.js viewer. The canvas is transparent
 * so the camera preview behind it shows through (AR-style overlay).
 *
 * Pinch-to-zoom is captured by a Compose [pointerInput] overlay above the
 * WebView (so we never fight the WebView's own scroll/zoom). Pinch deltas are
 * handed to [ThreeBridge.setFov].
 *
 * @param assetPath path inside `assets/`, default `viewer/index.html`
 * @param bridge    the [ThreeBridge] that will push orientation / FOV into the JS side
 * @param onFovChange called with the new FOV after a pinch; ViewModel should
 *   store it and (optionally) re-push via [ThreeBridge.setFov].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ThreeViewerWebView(
    assetPath: String = "viewer/index.html",
    bridge: ThreeBridge,
    currentFov: Float,
    onFovChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Capture the latest FOV in a State so the gesture detector can read it
    // without re-keying (re-keying would cancel an in-flight pinch gesture).
    val currentFovState = rememberUpdatedState(currentFov)
    val onFovChangeState = rememberUpdatedState(onFovChange)

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Transparent background so the camera preview shows through.
                setBackgroundColor(0x00000000)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = false
                // Note: ES modules + importmap work in Chromium 80+ WebView.
                // This covers all Android 8+ devices, and Android 7 via Play-update.

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        android.util.Log.e("ThreeViewerWebView",
                            "WebView error: ${error?.description} (code=${error?.errorCode})")
                    }
                }

                // Wire the bridge BEFORE loading so onReady() lands on the listener.
                bridge.attach(this)
                loadUrl("file:///android_asset/$assetPath")
            }
        },
        // A transparent gesture overlay sitting above the WebView (in z order
        // within this AndroidView tree) intercepts pinch gestures and routes
        // them to the bridge as FOV deltas.
        // pointerInput(Unit) keeps the gesture detector alive across recompositions;
        // currentFovState / onFovChangeState give us the latest values.
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom != 1f) {
                    // Pinch open (zoom > 1) -> narrower FOV.
                    val cur = currentFovState.value
                    val delta = (1f - zoom) * 60f   // sensitivity
                    val newFov = (cur + delta).coerceIn(20f, 110f)
                    onFovChangeState.value(newFov)
                }
            }
        }
    )
}
