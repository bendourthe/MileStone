package com.bendourthe.milestone.sync.webview

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.bendourthe.milestone.BuildConfig

/** The one hardened configuration every MileStone WebView uses. */
object WebViewConfig {

    /** True when each program can get its own cookie jar; false means all programs share one. */
    val supportsProfiles: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Applies the hardened settings. Must be called before the WebView loads anything,
     * because a profile can only be assigned to a WebView that has not been used yet.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, profileName: String) {
        if (supportsProfiles) {
            ProfileStore.getInstance().getOrCreateProfile(profileName)
            WebViewCompat.setProfile(webView, profileName)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        // A WebView attached without layout params gets a wrap_content height, and Chromium then lays pages out with
        // a zero viewport height: every `vh` length becomes 0 and sign-in panels sized in vh collapse out of view.
        if (webView.layoutParams == null) {
            webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            userAgentString = stripWebViewToken(WebSettings.getDefaultUserAgent(webView.context))
        }
        // Sites recognize a saved device through cookies set by their anti-fraud services on other domains; with
        // those blocked, "remember this device" never sticks and every sign-in asks for a second factor again.
        val cookies = if (supportsProfiles) ProfileStore.getInstance().getProfile(profileName)?.cookieManager else CookieManager.getInstance()
        (cookies ?: CookieManager.getInstance()).setAcceptThirdPartyCookies(webView, true)
        webView.setDownloadListener(null)
        webView.webChromeClient = DenyingChromeClient()
    }

    /** Removes the `; wv` marker sites use to detect embedded browsers. */
    fun stripWebViewToken(userAgent: String): String = userAgent.replace("; wv)", ")").replace("; wv", "")

    open class DenyingChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }
}
