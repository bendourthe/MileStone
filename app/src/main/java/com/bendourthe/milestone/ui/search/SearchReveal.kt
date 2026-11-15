package com.bendourthe.milestone.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.ui.benefits.CardPageUi

/** One memory-only result visit; it is never a route argument or saved-state value. */
class SearchReveal internal constructor(val target: SearchTarget, internal val snapshot: SearchSnapshot) {
    var expanded by mutableStateOf(false)
        private set
    var consumed by mutableStateOf(false)
        private set
    var invalidated by mutableStateOf(false)
        private set
    fun expand() { expanded = true }
    fun hideForVisit() { expanded = false }
    fun complete() { consumed = true }
    fun invalidate() { invalidated = true; consumed = true; expanded = false }
}

internal fun SearchValidation.message(): String = when (this) {
    SearchValidation.CURRENT -> ""
    SearchValidation.CHANGED -> "Results changed. Select the item again"
    SearchValidation.UNAVAILABLE -> "That stored item is no longer available"
    SearchValidation.READ_ERROR -> "Stored items could not be read. Please retry"
}

/** Mirrors the existing card list's stable slots, including optional panels and setup rows. */
internal fun benefitRevealSlots(page: CardPageUi, category: BenefitsCategory): List<String> = buildList {
    if (page.settingsError != null) add("settingsError")
    if (page.alerts.isNotEmpty()) add("alerts")
    if (page.listFreshnessNote != null && page.alerts.none { it.message == page.listFreshnessNote }) add("listFreshness")
    add("card")
    if (page.sections.all { it.items.isEmpty() }) add("empty")
    page.sections.forEach { section ->
        add("category:${section.category.name}")
        if (section.category == category || section.category !in page.preferences.collapsed) {
            if (section.category == BenefitsCategory.WELCOME_BONUS) add("welcomeSetup")
            addAll(section.items.map { it.key })
        }
    }
}

internal fun benefitRevealIndex(page: CardPageUi, target: SearchTarget.BenefitTarget): Int? {
    if (page.sections.none { it.category == target.category && it.items.any { item -> item.key == target.benefitKey } }) return null
    return benefitRevealSlots(page, target.category).indexOf(target.benefitKey).takeIf { it >= 0 }
}
