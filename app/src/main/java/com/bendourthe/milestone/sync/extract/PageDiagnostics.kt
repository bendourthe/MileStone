package com.bendourthe.milestone.sync.extract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Count-only facts the page script reports about one benefits page, used only for the diagnostics log. Built from
 * fixed values and range-checked integers, so a page script that calls the bridge cannot put text into the log.
 */
data class PageDiagnostics(
    val kind: String,
    val trackers: Int,
    val listed: Int,
    val waitedMs: Long,
    val bottom: Boolean,
    val activityLink: Boolean,
    val viewAllLink: Boolean,
    val switched: Boolean,
    val reason: String,
) {
    fun logLine(): String =
        "kind=$kind trackers=$trackers listed=$listed waited=$waitedMs bottom=$bottom activity=$activityLink " +
            "viewAll=$viewAllLink switched=$switched reason=$reason"

    companion object {
        val KINDS = setOf("rewards", "activity", "viewAll")
        val REASONS = setOf("stable", "empty", "cap", "timeout", "switchMismatch")

        /** No page waits longer than the whole benefits read (`SyncManager.BENEFITS_TIMEOUT_MS`). */
        const val MAX_WAITED_MS = 120_000L

        private val KEYS = setOf("kind", "trackers", "listed", "waitedMs", "bottom", "activityLink", "viewAllLink", "switched", "reason")
        private val WHOLE_NUMBER = Regex("^(0|[1-9][0-9]{0,8})$")

        /** Null for anything but an object with exactly the expected keys, fixed values, and in-range integers. */
        fun parse(value: JsonElement?): PageDiagnostics? {
            val json = value as? JsonObject ?: return null
            if (json.keys != KEYS) return null
            return PageDiagnostics(
                kind = json.fixed("kind", KINDS) ?: return null,
                trackers = json.count("trackers", BenefitsReader.MAX_TRACKERS.toLong())?.toInt() ?: return null,
                listed = json.count("listed", BenefitsReader.MAX_LISTED.toLong())?.toInt() ?: return null,
                waitedMs = json.count("waitedMs", MAX_WAITED_MS) ?: return null,
                bottom = json.flag("bottom") ?: return null,
                activityLink = json.flag("activityLink") ?: return null,
                viewAllLink = json.flag("viewAllLink") ?: return null,
                switched = json.flag("switched") ?: return null,
                reason = json.fixed("reason", REASONS) ?: return null,
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.fixed(key: String, allowed: Set<String>): String? =
            primitive(key)?.takeIf { it.isString && it.content in allowed }?.content

        /** A JSON integer literal (no fraction, exponent, sign, or quotes) from 0 to [max]. */
        private fun JsonObject.count(key: String, max: Long): Long? =
            primitive(key)?.takeIf { !it.isString && WHOLE_NUMBER.matches(it.content) }?.content?.toLong()?.takeIf { it <= max }

        private fun JsonObject.flag(key: String): Boolean? = primitive(key)?.takeIf { !it.isString }?.content?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }
}
