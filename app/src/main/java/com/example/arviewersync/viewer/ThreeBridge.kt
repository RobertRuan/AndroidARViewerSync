package com.example.arviewersync.viewer

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Two-way bridge between native Kotlin and the Three.js viewer running inside
 * the WebView.
 *
 * - **Native -> JS**: orientation / FOV / loadGltf / reset pushes via
 *   [android.webkit.WebView.evaluateJavascript] on the main thread. Orientation
 *   pushes are coalesced within a 16 ms window so we never push more than ~60
 *   times per second.
 * - **JS -> Native**: the [NativeBridge] inner class (annotated with
 *   `@JavascriptInterface`) is exposed as `window.NativeBridge`. JS calls it
 *   for `onReady`, `onFps`, `onModelLoaded`, `onModelError`.
 *
 * Construct with a WebView (after [WebView.addJavascriptInterface]) and call
 * the push methods from a collector.
 */
class ThreeBridge {
    private val handler = Handler(Looper.getMainLooper())
    private var webViewRef: WebView? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps
    private val _modelUrl = MutableStateFlow<String?>(null)
    val modelUrl: StateFlow<String?> = _modelUrl
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // pending orientation push (coalesced)
    @Volatile private var pendingYaw: Float = 0f
    @Volatile private var pendingPitch: Float = 0f
    @Volatile private var pendingRoll: Float = 0f
    @Volatile private var pendingRollComp: Boolean = true
    @Volatile private var hasPending: Boolean = false
    private var pushScheduled: Boolean = false

    fun attach(webView: WebView) {
        webViewRef = webView
        webView.addJavascriptInterface(NativeBridge(), "NativeBridge")
    }

    /** Called from the ViewModel's orientation collector — runs on any thread. */
    fun pushOrientation(yaw: Float, pitch: Float, roll: Float, rollComp: Boolean) {
        pendingYaw = yaw
        pendingPitch = pitch
        pendingRoll = roll
        pendingRollComp = rollComp
        hasPending = true
        if (!pushScheduled) {
            pushScheduled = true
            // Coalesce: dispatch on next ~16ms tick.
            handler.postDelayed(::flushPending, FRAME_MS)
        }
    }

    private fun flushPending() {
        pushScheduled = false
        if (!hasPending) return
        hasPending = false
        val y = pendingYaw
        val p = pendingPitch
        val r = pendingRoll
        val rc = if (pendingRollComp) 1 else 0
        // Minimal JS payload: no JSON, just direct numbers.
        val js = "window.__setOrientation&&window.__setOrientation($y,$p,$r,$rc);"
        handler.post {
            webViewRef?.evaluateJavascript(js, null)
        }
    }

    fun setFov(deg: Float) {
        val clamped = deg.coerceIn(20f, 110f)
        handler.post {
            webViewRef?.evaluateJavascript(
                "window.__setFov&&window.__setFov($clamped);", null
            )
        }
    }

    fun loadGltf(url: String) {
        handler.post {
            // Single-quote the URL inside the JS string; escape any embedded single quote.
            val safe = url.replace("\\", "\\\\").replace("'", "\\'")
            webViewRef?.evaluateJavascript(
                "window.__loadGltfUrl&&window.__loadGltfUrl('$safe');", null
            )
        }
    }

    fun resetView() {
        handler.post {
            webViewRef?.evaluateJavascript(
                "window.__resetView&&window.__resetView();", null
            )
        }
    }

    /** Inner class wired as `window.NativeBridge` — invoked from JS thread. */
    inner class NativeBridge {
        @JavascriptInterface
        fun onReady() {
            _ready.value = true
            Log.i(TAG, "Three.js viewer ready")
        }

        @JavascriptInterface
        fun onFps(fps: Int) {
            _fps.value = fps
        }

        @JavascriptInterface
        fun onModelLoaded(url: String) {
            _modelUrl.value = url
            _lastError.value = null
            Log.i(TAG, "Model loaded: $url")
        }

        @JavascriptInterface
        fun onModelError(error: String) {
            _lastError.value = error
            Log.e(TAG, "Model load error: $error")
        }
    }

    companion object {
        private const val TAG = "ThreeBridge"
        private const val FRAME_MS = 16L    // ~60 Hz cap
    }
}
