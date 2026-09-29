package com.soltini.app.bridge

import android.webkit.JavascriptInterface
import com.soltini.app.overlay.OverlayService

/**
 * Narrow JS bridge per spec §5 — only exposes onModelReady and onAvatarTapped.
 * The OverlayService handles all state and networking; this is read-only signalling.
 */
class AriaNativeBridge(private val service: OverlayService) {

    @JavascriptInterface
    fun onModelReady() {
        android.util.Log.d("AriaNativeBridge", "onModelReady called from WebView")
        service.onModelReady()
    }

    @JavascriptInterface
    fun onAvatarTapped() {
        android.util.Log.d("AriaNativeBridge", "onAvatarTapped called from WebView")
        service.onAvatarTapped()
    }
}
