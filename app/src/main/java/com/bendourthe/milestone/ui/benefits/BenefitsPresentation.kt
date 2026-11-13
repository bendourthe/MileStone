package com.bendourthe.milestone.ui.benefits

import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.BenefitsCategory
import java.util.Locale

data class PresentedBenefit(val benefit: Benefit, val enrollmentSource: Benefit?)

/** Pure display transformation. Callers keep the original rows for sync, reminders, and count verification. */
object BenefitsPresentation {
    private val amount = Regex("\\$\\s*\\d[\\d,]*(?:\\.\\d{1,2})?")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val descriptor = Regex("\\b(?:the|statement|credit|benefit)\\b")
    private val spaces = Regex("\\s+")

    private fun matchTitle(title: String): String = title.lowercase(Locale.ROOT)
        .replace(amount, " ").replace(punctuation, " ").replace(descriptor, " ").replace(spaces, " ").trim()

    fun category(benefit: Benefit): BenefitsCategory {
        val title = benefit.title.lowercase(Locale.ROOT)
        if ("welcome" in title || "new cardmember" in title || "new card member" in title) return BenefitsCategory.WELCOME_BONUS
        return when (benefit.section) {
            BenefitSection.CREDIT_EARNED -> BenefitsCategory.CREDITS
            BenefitSection.SPEND_PROGRESS -> BenefitsCategory.SPEND_TRACKERS
            BenefitSection.COMPLETED -> when {
                "credit" in title || "cash" in title -> BenefitsCategory.CREDITS
                "spend" in title -> BenefitsCategory.SPEND_TRACKERS
                else -> BenefitsCategory.OTHER
            }
            BenefitSection.LISTED, BenefitSection.OTHER -> BenefitsCategory.OTHER
        }
    }

    fun visible(benefits: List<Benefit>): List<Benefit> = presented(benefits).map { it.benefit }

    fun presented(benefits: List<Benefit>): List<PresentedBenefit> {
        val result = benefits.filter { it.section != BenefitSection.LISTED }.map { PresentedBenefit(it, it.takeIf { row -> row.enrollment != null }) }.toMutableList()
        val trackers = result.map { it.benefit }.filter { it.section != BenefitSection.OTHER }.groupBy { matchTitle(it.title) }
        benefits.filter { it.section == BenefitSection.LISTED }.forEach { listed ->
            val title = matchTitle(listed.title)
            val matches = if (title.isBlank()) emptyList() else trackers[title].orEmpty().filter { tracker ->
                listed.cardKey == tracker.cardKey &&
                    (listed.periodLabel.isNullOrBlank() || listed.periodLabel == tracker.periodLabel)
            }
            // A generic listed benefit cannot identify which of several trackers/periods it belongs to.
            val tracker = matches.singleOrNull()
            if (tracker == null) {
                result += PresentedBenefit(listed, listed.takeIf { it.enrollment != null })
            } else {
                val index = result.indexOfFirst { it.benefit.stableKey == tracker.stableKey && it.benefit.cardKey == tracker.cardKey }
                val previous = result[index]
                val current = previous.benefit
                // Conflicting enrollment marks are evidence of ambiguity, not permission to hide a row.
                if (current.enrollment != null && listed.enrollment != null && current.enrollment != listed.enrollment) {
                    result += PresentedBenefit(listed, listed.takeIf { it.enrollment != null })
                } else {
                    result[index] = PresentedBenefit(current.copy(
                        enrollment = current.enrollment ?: listed.enrollment,
                        description = listOfNotNull(current.description, listed.description).distinct()
                            .joinToString("\n\n").ifEmpty { null },
                    ), previous.enrollmentSource ?: listed.takeIf { it.enrollment != null })
                }
            }
        }
        return result
    }

    fun groups(benefits: List<Benefit>, completedLast: Boolean): Map<BenefitsCategory, List<Benefit>> {
        val visible = visible(benefits)
        val ordering = compareBy<Benefit> { if (completedLast && it.completed) 1 else 0 }
            .thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.stableKey }
        return BenefitsCategory.entries.associateWith { group -> visible.filter { category(it) == group }.sortedWith(ordering) }
    }
}
