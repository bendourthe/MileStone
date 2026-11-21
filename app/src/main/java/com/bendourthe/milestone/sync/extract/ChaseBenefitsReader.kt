package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.PeriodLength
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One card as the Chase card-selector sheet lists it. [control] is the page's control id: kept in memory only. */
data class ChaseCardOption(val control: String, val name: String, val lastDigits: String?, val selected: Boolean) {
    override fun toString() = "ChaseCardOption(control=<redacted>, name=<redacted>, lastDigits=<redacted>, selected=$selected)"
}

/**
 * Validates Chase benefits messages from `extract.js` (ADR-0009) and turns rows and trackers into drafts using only
 * what the page states: a row's amount and period from its value text, its enrollment and completion from its tag,
 * and a tracker's earned and target amounts only from dollar amounts it shows. Nothing is invented: a missing or
 * unparseable value stays null.
 */
object ChaseBenefitsReader {
    const val MAX_CARDS = 20
    const val MAX_GROUPS = 30
    const val MAX_ROWS = 150
    const val MAX_TRACKERS = 20
    private val CONTROL = Regex("^[A-Za-z0-9_:.\\-]{1,256}$")
    private val PAN = Regex("(?:\\d[_.:\\-]*){12}\\d")
    private val MONEY = Regex("\\$\\s?(\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.(\\d{2}))?")

    /** An amount stated as progress toward another: "$1,500 of $4,000" or "$1,500 / $4,000". */
    private val PROGRESS = Regex("\\$\\s?[\\d,]+(?:\\.\\d{2})?\\s*(?:of|/)\\s*\\$", RegexOption.IGNORE_CASE)

    /** A gauge's accessible label stating progress, at most two words before "of": "$1,500 received of $4,000". */
    private val STATED = Regex(
        "(\\$\\s?[\\d,]+(?:\\.\\d{2})?)\\s*(?:[a-z]+\\s+){0,2}(?:of|/)\\s*(\\$\\s?[\\d,]+(?:\\.\\d{2})?)",
        RegexOption.IGNORE_CASE,
    )
    private val TRACKER_KEYS = setOf("text", "value", "max")

    fun isControl(value: String?): Boolean = value != null && CONTROL.matches(value) && !PAN.containsMatchIn(value)

    /** The sheet's cards; null when malformed. More than one selected card is malformed. */
    fun cards(element: JsonElement?): List<ChaseCardOption>? {
        val array = element as? JsonArray ?: return null
        if (array.size > MAX_CARDS) return null
        val cards = array.map { item ->
            val obj = item as? JsonObject ?: return null
            if (obj.keys != setOf("control", "name", "lastDigits", "selected")) return null
            val control = obj.text("control")?.takeIf(::isControl) ?: return null
            val name = obj.text("name")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return null
            val digits = obj["lastDigits"].let { if (it is JsonNull) null else (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(CardKey::isValidLastDigits) ?: return null }
            val selected = (obj["selected"] as? JsonPrimitive)?.booleanOrNull ?: return null
            ChaseCardOption(control, name, digits, selected)
        }
        if (cards.count { it.selected } > 1 || cards.map { it.control }.toSet().size != cards.size) return null
        return cards
    }

    /** Drafts for one card's page; null when malformed or over the caps. */
    fun benefits(groups: JsonElement?, trackers: JsonElement?): List<BenefitDraft>? {
        val groupArray = groups as? JsonArray ?: return null
        val trackerArray = trackers as? JsonArray ?: return null
        if (groupArray.size > MAX_GROUPS || trackerArray.size > MAX_TRACKERS) return null
        val drafts = mutableListOf<BenefitDraft>()
        var rows = 0
        for (group in groupArray) {
            val obj = group as? JsonObject ?: return null
            if (obj.keys != setOf("title", "rows")) return null
            obj.optionalOrNull("title", 80) ?: return null
            val list = obj["rows"] as? JsonArray ?: return null
            for (row in list) {
                if (++rows > MAX_ROWS) return null
                drafts += row(row as? JsonObject ?: return null) ?: return null
            }
        }
        for (tracker in trackerArray) drafts += tracker(tracker as? JsonObject ?: return null) ?: return null
        return drafts
    }

    private fun row(obj: JsonObject): BenefitDraft? {
        if (obj.keys != setOf("title", "tag", "value")) return null
        val title = obj.text("title")?.takeIf { it.isNotBlank() && it.length <= 120 } ?: return null
        val tag = obj.optionalOrNull("tag", 40) ?: return null
        val value = obj.optionalOrNull("value", 60) ?: return null
        val tagText = tag.value?.lowercase().orEmpty()
        val amounts = value.value?.let(::amounts).orEmpty()
        return BenefitDraft(
            section = BenefitSection.LISTED,
            title = title,
            earnedCents = null,
            remainingCents = null,
            targetCents = amounts.singleOrNull(),
            periodLabel = null,
            completed = tagText.contains("redeemed") || tagText.contains("used") || tagText.contains("completed"),
            periodLength = value.value?.let(::periodOf),
            description = value.value,
            enrollment = when {
                tagText.contains("activated") || tagText.contains("enrolled") -> Enrollment.ENROLLED
                tagText.contains("activate") || tagText.contains("enroll") -> Enrollment.ENROLLMENT_REQUIRED
                else -> null
            },
        )
    }

    private fun tracker(obj: JsonObject): BenefitDraft? {
        if (obj.keys != TRACKER_KEYS && obj.keys != TRACKER_KEYS + "label") return null
        val text = obj.text("text")?.takeIf { it.isNotBlank() && it.length <= 300 } ?: return null
        val value = obj.number("value") ?: return null
        val max = obj.number("max") ?: return null
        val label = if ("label" in obj.keys) (obj.optionalOrNull("label", 120) ?: return null).value else null
        // The gauge's own label names both amounts, so it is the most direct statement of progress.
        val stated = label?.let(STATED::find)?.let { amounts(it.groupValues[1]).single() to amounts(it.groupValues[2]).single() }
            ?.takeIf { (_, goal) -> goal > 0 }
        val money = amounts(text)
        val target = stated?.second ?: money.maxOrNull()
        // Earned only when the page states it: "$X of $Y" or "$X / $Y", or a bar whose maximum is the target. Two
        // amounts alone are not progress: "Earn $300 after you spend $3,000" names a reward and a target.
        val earned = when {
            stated != null -> stated.first
            money.size >= 2 && money.first() <= money.last() && PROGRESS.containsMatchIn(text) -> money.first()
            target != null && max.value != null && value.value != null && Math.round(max.value * 100) == target -> Math.round(value.value * 100)
            else -> null
        }
        return BenefitDraft(
            section = BenefitSection.SPEND_PROGRESS,
            title = text.substringBefore(". ").take(120),
            earnedCents = earned,
            remainingCents = if (earned != null && target != null) (target - earned).coerceAtLeast(0) else null,
            targetCents = target,
            periodLabel = null,
            completed = earned != null && target != null && earned >= target,
            description = text.takeIf { it.length > 120 },
        )
    }

    /** Every dollar amount in [text], in cents and in order. */
    internal fun amounts(text: String): List<Long> = MONEY.findAll(text).mapNotNull { match ->
        val whole = match.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
        whole * 100 + (match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0)
    }.toList()

    internal fun periodOf(text: String): PeriodLength? {
        val lower = text.lowercase()
        return when {
            // Half-year forms first: "every 6 months" also contains "month".
            "semi" in lower || "half" in lower || "6 months" in lower || "six months" in lower -> PeriodLength.HALF_YEAR
            "month" in lower -> PeriodLength.MONTHLY
            "quarter" in lower -> PeriodLength.QUARTERLY
            "annual" in lower || "year" in lower -> PeriodLength.YEARLY
            else -> null
        }
    }

    private class Optional(val value: String?)
    private class Number(val value: Double?)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A string of at most [max] characters or JSON null; Kotlin null when the field is anything else. */
    private fun JsonObject.optionalOrNull(key: String, max: Int): Optional? = when (val field = this[key]) {
        is JsonNull -> Optional(null)
        is JsonPrimitive -> if (field.isString && field.content.length <= max) Optional(field.content) else null
        else -> null
    }

    private fun JsonObject.number(key: String): Number? = when (val field = this[key]) {
        is JsonNull -> Number(null)
        is JsonPrimitive -> if (field.isString) null else field.doubleOrNull?.takeIf { it in 0.0..1e9 }?.let(::Number)
        else -> null
    }
}
