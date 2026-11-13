package com.bendourthe.milestone.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.BenefitsSort
import com.bendourthe.milestone.domain.BenefitsReadState
import com.bendourthe.milestone.domain.BenefitsCategory
import com.bendourthe.milestone.domain.BenefitsDisplayPreferences
import com.bendourthe.milestone.domain.BenefitsDisplaySettings
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.WelcomeBonusSetup
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate

class DataStoreBenefitsDisplaySettings(private val store: DataStore<Preferences>) : BenefitsDisplaySettings {
    private fun prefix(card: CardKey) = "benefitsDisplay:${card.programId}:${card.lastDigits}:"
    private fun sortKey(card: CardKey) = stringPreferencesKey(prefix(card) + "sort")
    private fun completed(card: CardKey) = booleanPreferencesKey(prefix(card) + "completedLast")
    private fun collapsedKey(card: CardKey) = stringSetPreferencesKey(prefix(card) + "collapsed")
    private fun acknowledgements(card: CardKey) = stringSetPreferencesKey(prefix(card) + "acknowledged")
    private fun spent(card: CardKey) = longPreferencesKey(prefix(card) + "welcomeSpent")
    private fun target(card: CardKey) = longPreferencesKey(prefix(card) + "welcomeTarget")
    private fun deadline(card: CardKey) = longPreferencesKey(prefix(card) + "welcomeDeadline")
    private fun reward(card: CardKey) = stringPreferencesKey(prefix(card) + "welcomeReward")

    private fun generation(card: CardKey) = longPreferencesKey(prefix(card) + "readGeneration")
    private fun cardVersion(card: CardKey) = longPreferencesKey(prefix(card) + "cardVersion")
    private fun readTime(card: CardKey) = longPreferencesKey(prefix(card) + "readTime")
    private fun sectionGeneration(card: CardKey, section: BenefitSection) = longPreferencesKey(prefix(card) + "read:" + section.name)

    override fun observe(card: CardKey) = store.data.catch { error ->
        if (error !is IOException) throw error
        Log.w("MileStoneSettings", "Benefits display settings unreadable; using defaults")
        emit(emptyPreferences())
    }.map { prefs ->
        val savedCollapsed = prefs[collapsedKey(card)]
        val welcome = runCatching {
            prefs[target(card)]?.let { goal ->
                WelcomeBonusSetup(prefs[spent(card)] ?: 0, goal, prefs[deadline(card)]?.let(LocalDate::ofEpochDay), prefs[reward(card)].orEmpty())
            }
        }.getOrNull()
        BenefitsDisplayPreferences(
            completedLast = prefs[completed(card)] ?: false,
            sort = BenefitsSort.entries.firstOrNull { it.name == prefs[sortKey(card)] } ?: BenefitsSort.NAME_ASC,
            collapsed = savedCollapsed?.mapNotNull { name -> BenefitsCategory.entries.firstOrNull { it.name == name } }?.toSet()
                ?: setOf(BenefitsCategory.OTHER),
            acknowledged = prefs[acknowledgements(card)].orEmpty().mapNotNull { entry ->
                val cut = entry.indexOf('\u001e')
                if (cut < 0) null else entry.substring(0, cut).toLongOrNull()?.let { entry.substring(cut + 1) to it }
            }.toMap(),
            welcomeBonus = welcome,
            readState = BenefitsReadState(prefs[generation(card)] ?: 0, prefs[readTime(card)],
                BenefitSection.entries.mapNotNull { section -> prefs[sectionGeneration(card, section)]?.let { section to it } }.toMap(), prefs[cardVersion(card)] ?: 0),
        )
    }.distinctUntilChanged()

    override suspend fun setSort(card: CardKey, sort: BenefitsSort) {
        store.edit { it[sortKey(card)] = sort.name }
    }

    override suspend fun setCompletedLast(card: CardKey, completedLast: Boolean) {
        store.edit { it[completed(card)] = completedLast }
    }

    override suspend fun setCollapsed(card: CardKey, category: BenefitsCategory, collapsed: Boolean) {
        store.edit { prefs ->
            val key = collapsedKey(card)
            val groups = prefs[key] ?: setOf(BenefitsCategory.OTHER.name)
            prefs[key] = if (collapsed) groups + category.name else groups - category.name
        }
    }

    override suspend fun acknowledge(card: CardKey, alertId: String, readGeneration: Long) {
        store.edit { prefs ->
            val key = acknowledgements(card)
            prefs[key] = prefs[key].orEmpty().filterNot { it.substringAfter('\u001e', "") == alertId }.toSet() +
                "$readGeneration\u001e$alertId"
        }
    }

    override suspend fun setWelcomeBonus(card: CardKey, setup: WelcomeBonusSetup?) {
        store.edit { prefs ->
            if (setup == null) {
                prefs.remove(spent(card)); prefs.remove(target(card)); prefs.remove(deadline(card)); prefs.remove(reward(card))
            } else {
                prefs[spent(card)] = setup.spentCents
                prefs[target(card)] = setup.targetCents
                prefs[reward(card)] = setup.rewardLabel
                if (setup.deadline == null) prefs.remove(deadline(card)) else prefs[deadline(card)] = setup.deadline.toEpochDay()
            }
        }
    }
    override suspend fun recordReadOutcome(card: CardKey, readEpochMs: Long, cardGeneration: Long, sections: Set<BenefitSection>) {
        if (sections.isEmpty()) return
        store.edit { prefs ->
            val next = Math.addExact(prefs[generation(card)] ?: 0, 1)
            prefs[generation(card)] = next
            prefs[readTime(card)] = readEpochMs
            prefs[cardVersion(card)] = cardGeneration
            sections.forEach { prefs[sectionGeneration(card, it)] = next }
        }
    }

}
