package com.bendourthe.milestone.sync.webview

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewConfigTest {
    @Test
    fun acceptsThirdPartyCookiesSoSavedDevicesStickWhileKeepingTheHardening() {
        val profile = "program_webviewconfigtest"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val webView = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
            WebViewConfig.configure(webView, profile)
            val cookies = if (WebViewConfig.supportsProfiles) ProfileStore.getInstance().getProfile(profile)!!.cookieManager else CookieManager.getInstance()

            assertTrue(cookies.acceptThirdPartyCookies(webView))
            with(webView.settings) {
                assertFalse(allowFileAccess)
                assertFalse(allowContentAccess)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                assertFalse(javaScriptCanOpenWindowsAutomatically)
            }
            // WebView frees a profile only after destroy finishes, so deleting it here always throws; uninstalling the test app removes it.
            webView.destroy()
        }
    }
}
