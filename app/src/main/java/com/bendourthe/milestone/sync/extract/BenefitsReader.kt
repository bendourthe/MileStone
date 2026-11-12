package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Enrollment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Parses benefit amounts such as "$75.00", "$1,234", or "$71,230 To Go" into whole cents. */
object BenefitAmountParser {
    private val dollars = Regex("""\$\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""")

    /** The first dollar amount in [raw], or null when there is none or it is implausibly large. */
    fun parse(raw: String?): Long? {
        val match = dollars.find(raw ?: return null) ?: return null
        val whole = match.groupValues[1].filter(Char::isDigit).toLongOrNull() ?: return null
        val cents = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        return (whole * 100 + cents).takeIf { whole <= MAX_DOLLARS }
    }

    private const val MAX_DOLLARS = 10_000_000L
}

/**
 * Turns the page script's raw benefit messages into validated drafts (ADR-0008). Every object must carry only
 * known fields of the right type and length; anything else drops the whole message, so a changed page or an
 * unexpected field never stores partial or unchecked data.
 */
object BenefitsReader {
    const val MAX_CARDS = 20
    const val MAX_TRACKERS = 60
    const val MAX_LISTED = 150

    private val CARD_FIELDS = setOf("name", "lastDigits")
    private val TRACKER_FIELDS = setOf("group", "title", "spent", "remaining", "target", "period", "done", "description")
    private val LISTED_FIELDS = setOf("key", "title", "description", "enrollment")
    private val ISSUER_KEY = Regex("^[A-Za-z0-9+#-]{1,80}$")
    private val SUMMARY = Regex("^congratulations", RegexOption.IGNORE_CASE)

    /** Null when the list is malformed; otherwise a [CardList] whose completeness also reflects dropped entries. */
    fun cards(value: JsonElement?, complete: Boolean): CardList? {
        val items = (value as? JsonArray)?.takeIf { it.size <= MAX_CARDS } ?: return null
        val drafts = items.map { item ->
            val card = item.fields(CARD_FIELDS) ?: return null
            CardDraft(card.text("name", 80) ?: return null, card.text("lastDigits", 5) ?: return null)
        }
        return CardList.from(drafts, complete)
    }

    /** Trackers first in page order, then the listed benefits; null when either list is malformed. */
    fun benefits(trackers: JsonElement?, listed: JsonElement?): List<BenefitDraft>? {
        val trackerItems = (trackers as? JsonArray)?.takeIf { it.size <= MAX_TRACKERS } ?: return null
        val listedItems = (listed as? JsonArray)?.takeIf { it.size <= MAX_LISTED } ?: return null
        val tracked = trackerItems.mapNotNull { (tracker(it) ?: return null).draft }
        val all = listedItems.map { listedBenefit(it) ?: return null }
        return tracked + all
    }

    /** One tracker read; [draft] is null for the yearly "Congratulations" summary, which is not a benefit. */
    private class TrackerRead(val draft: BenefitDraft?)

    /** Null when malformed. */
    private fun tracker(item: JsonElement): TrackerRead? {
        val t = item.fields(TRACKER_FIELDS) ?: return null
        val title = t.text("title", 120) ?: return null
        val done = (t["done"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val group = t.text("group", 60)
        val spent = t.text("spent", 40)
        val remaining = t.text("remaining", 60)
        val period = t.text("period", 40)
        val targetDollars = (t["target"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
        if (SUMMARY.containsMatchIn(title) && spent == null && remaining == null) return TrackerRead(null)
        val earned = BenefitAmountParser.parse(spent)
        val left = BenefitAmountParser.parse(remaining)
        val target = if (earned != null && left != null) earned + left else targetDollars?.let { Math.round(it * 100) }
        val section = section(group, title, done, period, remaining, target)
        return TrackerRead(
            BenefitDraft(
                section = section,
                title = title,
                earnedCents = earned,
                remainingCents = left,
                targetCents = target,
                periodLabel = period,
                completed = done || section == BenefitSection.COMPLETED,
                description = t.text("description", 300),
            ),
        )
    }

    private fun listedBenefit(item: JsonElement): BenefitDraft? {
        val l = item.fields(LISTED_FIELDS) ?: return null
        val key = l.text("key", 80)?.takeIf { ISSUER_KEY.matches(it) } ?: return null
        val enrollment = when (l.text("enrollment", 40)) {
            null -> null
            "Enrolled" -> Enrollment.ENROLLED
            "Enrollment Required" -> Enrollment.ENROLLMENT_REQUIRED
            else -> return null
        }
        return BenefitDraft(
            section = BenefitSection.LISTED,
            title = l.text("title", 120) ?: return null,
            earnedCents = null,
            remainingCents = null,
            targetCents = null,
            periodLabel = null,
            completed = false,
            description = l.text("description", 300),
            enrollment = enrollment,
            issuerKey = key,
        )
    }

    /**
     * The activity page names its groups ("Spend Progress", "Credit Earned", "Completed Benefits"); the Rewards page's
     * "Track Your Progress" grid does not, so there a check mark means completed, a period or "credit" in the title
     * means a statement credit, and a target means spend progress.
     */
    fun section(group: String?, title: String, done: Boolean, period: String?, remaining: String?, target: Long?): BenefitSection {
        val g = group.orEmpty().lowercase()
        return when {
            "complete" in g -> BenefitSection.COMPLETED
            "credit" in g -> BenefitSection.CREDIT_EARNED
            "spend" in g -> BenefitSection.SPEND_PROGRESS
            done -> BenefitSection.COMPLETED
            period != null || "credit" in title.lowercase() -> BenefitSection.CREDIT_EARNED
            target != null || remaining?.contains("to go", ignoreCase = true) == true -> BenefitSection.SPEND_PROGRESS
            else -> BenefitSection.OTHER
        }
    }

    private fun JsonElement.fields(allowed: Set<String>): JsonObject? = (this as? JsonObject)?.takeIf { allowed.containsAll(it.keys) }

    private fun JsonObject.text(key: String, max: Int): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.content.takeIf { primitive.isString && it.length <= max }?.trim()?.takeIf { it.isNotEmpty() }
    }
}
