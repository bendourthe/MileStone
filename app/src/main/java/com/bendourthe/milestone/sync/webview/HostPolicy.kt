package com.bendourthe.milestone.sync.webview

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Decides whether a URL belongs to a program's first-party hosts.
 *
 * A host matches an allowed host only when it is equal to it or is a subdomain at a dot
 * boundary, so `evilchase.com` and `chase.com.evil.io` never match `chase.com`.
 */
object HostPolicy {
    fun isAllowed(url: String, allowedHosts: Collection<String>): Boolean {
        val host = httpsHost(url) ?: return false
        return allowedHosts.any { matches(host, normalize(it)) }
    }

    /** The lowercase host of an `https` URL with no user info and the default port, or null. */
    fun httpsHost(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host ?: return null
        if (host.isEmpty() || host.startsWith("[")) return null
        return normalize(host)
    }

    private fun matches(host: String, allowed: String): Boolean =
        allowed.isNotEmpty() && (host == allowed || host.endsWith(".$allowed"))

    private fun normalize(host: String): String = host.trim().trimEnd('.').lowercase(Locale.ROOT)
}
