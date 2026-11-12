package com.bendourthe.milestone.sync.mfa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A code read from a text, held in memory only until one sign-in uses it. [toString] never prints the code. */
class OneTimeCode(val value: String, val receivedEpochMs: Long, internal val body: String) {
    override fun toString() = "OneTimeCode(<redacted>)"
}

/**
 * Codes from incoming texts. Each is kept for [TTL_MS] at most, handed out once, and only to a program whose name
 * the text mentions. Nothing is written to disk or logged.
 */
class OneTimeCodeInbox(private val clock: () -> Long = System::currentTimeMillis) {
    private val codes = MutableStateFlow<List<OneTimeCode>>(emptyList())

    fun offer(body: String) {
        val value = OneTimeCodeParser.extract(body) ?: return
        val now = clock()
        codes.value = codes.value.filter { now - it.receivedEpochMs < TTL_MS } + OneTimeCode(value, now, body.lowercase())
    }

    /** Waits up to [timeoutMs] for a code that arrived after [sinceEpochMs] and names one of [keywords]. */
    suspend fun await(keywords: Set<String>, sinceEpochMs: Long, timeoutMs: Long): String? {
        if (keywords.isEmpty()) return null
        return withTimeoutOrNull(timeoutMs) {
            codes.first { list -> list.any { it.matches(keywords, sinceEpochMs) } }
            take(keywords, sinceEpochMs)
        }
    }

    @Synchronized
    private fun take(keywords: Set<String>, sinceEpochMs: Long): String? {
        val match = codes.value.lastOrNull { it.matches(keywords, sinceEpochMs) } ?: return null
        codes.value -= match
        return match.value
    }

    private fun OneTimeCode.matches(keywords: Set<String>, sinceEpochMs: Long): Boolean =
        receivedEpochMs >= sinceEpochMs && clock() - receivedEpochMs < TTL_MS && keywords.any { namesKeyword(body, it) }

    companion object {
        const val TTL_MS = 5 * 60_000L

        /** [keyword] as a whole word, so "purchase" never passes for "chase" and another sender's code is not taken. */
        fun namesKeyword(body: String, keyword: String): Boolean =
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(keyword) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(body)
    }
}

object OneTimeCodeParser {
    private val HINT = Regex("\\b(code|passcode|verification|verify|one[- ]time|otp|security|identification)\\b", RegexOption.IGNORE_CASE)
    /** A 4 to 8 digit run that is not part of an amount, a time, a date, or a phone number. */
    private val CODE = Regex("(?<![\\d$])(?<!\\d[.,:/-])(\\d{4,8})(?!\\d|[.,:/-]\\d|%)")

    /** The code in a verification text, or null when the text has no code-like wording or no single code. */
    fun extract(body: String): String? {
        if (!HINT.containsMatchIn(body)) return null
        val candidates = CODE.findAll(body).map { it.groupValues[1] }.distinct().toList()
        return candidates.singleOrNull()
    }
}
