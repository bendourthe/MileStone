package com.bendourthe.milestone.sync.background

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import com.bendourthe.milestone.sync.WebViewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lends a WebView in a window on a private, screen-sized virtual display that only this app draws on and nobody
 * sees. Pages render and run timers as if on screen, so refreshes work while the phone is locked.
 */
class VirtualDisplayWebViewHost(private val context: Context) : WebViewHost {
    override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T = withContext(Dispatchers.Main) {
        val metrics = context.resources.displayMetrics
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            DISPLAY_NAME, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val presentation = Presentation(context, display.display)
        val webView = WebView(presentation.context)
        try {
            presentation.setContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            presentation.show()
            block(webView)
        } finally {
            presentation.dismiss()
            webView.destroy()
            display.release()
            reader.close()
        }
    }

    private companion object {
        const val DISPLAY_NAME = "MileStone refresh"
    }
}
