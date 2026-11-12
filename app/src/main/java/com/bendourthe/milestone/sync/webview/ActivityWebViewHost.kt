package com.bendourthe.milestone.sync.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.bendourthe.milestone.sync.WebViewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lends an invisible, non-focusable WebView behind [activity]'s content. It is screen-sized because sites such as
 * Chase render dashboard tiles only once they scroll into a real viewport; a 1x1 view never shows the balance.
 */
class ActivityWebViewHost(private val activity: Activity) : WebViewHost {
    @SuppressLint("ClickableViewAccessibility")
    override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T = withContext(Dispatchers.Main) {
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
        val webView = WebView(activity).apply {
            alpha = 0f
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnTouchListener { _, _ -> true }
        }
        parent.addView(webView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            block(webView)
        } finally {
            parent.removeView(webView)
            webView.destroy()
        }
    }
}
