package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.normalizeBenefitTitle
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
    const val MAX_HIGHLIGHTS = 20

    private val CARD_FIELDS = setOf("name", "lastDigits")
    private val TRACKER_FIELDS = setOf("group", "title", "spent", "remaining", "target", "period", "label", "done", "description")
    private val LISTED_FIELDS = setOf("key", "title", "description", "enrollment")
    private val ISSUER_KEY = Regex("^[A-Za-z0-9+#-]{1,80}$")
    private val SUMMARY = Regex("^congratulations", RegexOption.IGNORE_CASE)
    private val WORD = Regex("[a-z]+")

    /** A finished tracker's own wording: "earned a Free Night Reward", up to six capitalized words. */
    private val EARNED = Regex("\\b(?:earned|received|unlocked) (?:a|an|your) ([A-Z][A-Za-z]*(?: [A-Z][A-Za-z]*){1,5})")
    private val COMMON_WORDS = setOf("earn", "the", "and", "for", "you", "your", "with", "get", "from", "per", "this", "year")

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

    /**
     * Amex retitles a finished tracker "Congratulations!" and names the benefit only in its description ("You've
     * earned a Free Night Reward."), while the card's benefits list may come from another page or an earlier read.
     * Each such tracker takes the title of the one listed benefit (in [drafts] or [extraTitles]) whose distinctive
     * words its description contains. When [final] (the card is being saved), a tracker no title matches is named
     * from its description's own words ("earned a Free Night Reward"), and one whose description names nothing is
     * dropped, as the yearly summary always was; otherwise it is kept for a later step to name.
     */
    fun nameFinished(drafts: List<BenefitDraft>, extraTitles: List<String> = emptyList(), final: Boolean = false): List<BenefitDraft> {
        val titles = (drafts.filter { it.section == BenefitSection.LISTED }.map { it.title } + extraTitles).distinct()
        return drafts.mapNotNull { draft ->
            if (!isUnnamedFinished(draft)) return@mapNotNull draft
            val note = draft.description.orEmpty()
            val said = words(note)
            val named = titles.filter { title -> words(title).let { it.size >= 2 && said.containsAll(it) } }
                .distinctBy(::normalizeBenefitTitle).singleOrNull()
                ?: if (final) EARNED.find(note)?.groupValues?.get(1) ?: return@mapNotNull null else return@mapNotNull draft
            val credit = "credit" in named.lowercase()
            draft.copy(title = named, section = if (credit) BenefitSection.CREDIT_EARNED else BenefitSection.SPEND_PROGRESS)
        }
    }

    /** True when [drafts] hold a finished tracker still waiting for its name. */
    fun hasUnnamedFinished(drafts: List<BenefitDraft>): Boolean = drafts.any(::isUnnamedFinished)

    /** The Rewards page's highlight titles; null when malformed. Names only, never posted further. */
    fun highlights(element: JsonElement): List<String>? {
        val array = element as? JsonArray ?: return null
        if (array.size > MAX_HIGHLIGHTS) return null
        return array.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 120 }?.content ?: return null }
    }

    private fun isUnnamedFinished(draft: BenefitDraft): Boolean =
        draft.section == BenefitSection.SPEND_PROGRESS && draft.completed && SUMMARY.containsMatchIn(draft.title) &&
            draft.earnedCents == null && draft.remainingCents == null

    private fun words(text: String): Set<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 && it !in COMMON_WORDS }.toSet()

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
        if (SUMMARY.containsMatchIn(title) && spent == null && remaining == null) {
            // Checked as done with a description: possibly a finished benefit, named once the list is known.
            val note = t.text("description", 300)?.takeIf { done } ?: return TrackerRead(null)
            return TrackerRead(BenefitDraft(BenefitSection.SPEND_PROGRESS, title, null, null, null, null, completed = true, description = note))
        }
        val earned = BenefitAmountParser.parse(spent)
        val left = BenefitAmountParser.parse(remaining)
        val target = if (earned != null && left != null) earned + left else targetDollars?.let { Math.round(it * 100) }
        val section = section(group, title, done, period, remaining, target, t.text("label", 20))
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
     * "Track Your Progress" grid does not, so there a check mark means completed, the word beside the amount ("spent"
     * or "earned") decides next, then a period or "credit" in the title means a statement credit, and a target means
     * spend progress. A spend threshold is never a credit, so it never gets a reminder.
     */
    fun section(
        group: String?,
        title: String,
        done: Boolean,
        period: String?,
        remaining: String?,
        target: Long?,
        label: String? = null,
    ): BenefitSection {
        val g = group.orEmpty().lowercase()
        val word = label.orEmpty().lowercase()
        return when {
            "complete" in g -> BenefitSection.COMPLETED
            "credit" in g -> BenefitSection.CREDIT_EARNED
            "spend" in g -> BenefitSection.SPEND_PROGRESS
            done -> BenefitSection.COMPLETED
            "spent" in word -> BenefitSection.SPEND_PROGRESS
            "earned" in word -> BenefitSection.CREDIT_EARNED
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
