package com.bendourthe.milestone.sync.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPolicyTest {
    private val chase = listOf("chase.com")

    @Test
    fun acceptsExactHostAndSubdomains() {
        assertTrue(HostPolicy.isAllowed("https://chase.com/", chase))
        assertTrue(HostPolicy.isAllowed("https://secure.chase.com/web/auth", chase))
        assertTrue(HostPolicy.isAllowed("https://a.b.chase.com", chase))
        assertTrue(HostPolicy.isAllowed("https://SECURE.Chase.COM/path", chase))
        assertTrue(HostPolicy.isAllowed("https://secure.chase.com./", chase))
        assertTrue(HostPolicy.isAllowed("https://secure.chase.com:443/", chase))
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertFalse(HostPolicy.isAllowed("https://evilchase.com", chase))
        assertFalse(HostPolicy.isAllowed("https://chase.com.evil.io", chase))
        assertFalse(HostPolicy.isAllowed("https://xn--chse-una.com", chase))
        assertFalse(HostPolicy.isAllowed("https://xn--hase-zza.com", chase))
        assertFalse(HostPolicy.isAllowed("https://ch\u0430se.com", chase))
    }

    @Test
    fun rejectsUserInfoPortsAndNonHttps() {
        assertFalse(HostPolicy.isAllowed("https://chase.com@evil.com", chase))
        assertFalse(HostPolicy.isAllowed("https://user:pw@chase.com/", chase))
        assertFalse(HostPolicy.isAllowed("http://chase.com", chase))
        assertFalse(HostPolicy.isAllowed("https://chase.com:8443/", chase))
        assertFalse(HostPolicy.isAllowed("javascript:alert(1)", chase))
        assertFalse(HostPolicy.isAllowed("file:///sdcard/chase.com", chase))
        assertFalse(HostPolicy.isAllowed("not a url", chase))
        assertFalse(HostPolicy.isAllowed("https://[::1]/", chase))
    }

    @Test
    fun emptyAllowListAllowsNothing() {
        assertFalse(HostPolicy.isAllowed("https://chase.com", emptyList()))
        assertFalse(HostPolicy.isAllowed("https://chase.com", listOf("")))
    }

    @Test
    fun httpsHostNormalizes() {
        assertEquals("secure.chase.com", HostPolicy.httpsHost("https://Secure.Chase.com./x"))
        assertNull(HostPolicy.httpsHost("http://chase.com"))
    }
}
