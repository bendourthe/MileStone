package com.bendourthe.milestone.sync.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentTest {
    @Test
    fun removesWebViewToken() {
        val ua = "Mozilla/5.0 (Linux; Android 15; Pixel 9; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0 Mobile Safari/537.36",
            WebViewConfig.stripWebViewToken(ua),
        )
    }

    @Test
    fun leavesOrdinaryUserAgentAlone() {
        val ua = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36"
        assertEquals(ua, WebViewConfig.stripWebViewToken(ua))
    }
}
