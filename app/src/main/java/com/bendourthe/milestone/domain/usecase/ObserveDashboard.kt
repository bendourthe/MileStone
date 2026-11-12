package com.bendourthe.milestone.domain.usecase

import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DashboardEntry(val program: Program, val account: LoyaltyAccount)

/** Tracked accounts joined with their catalog programs, in the requested order. */
class ObserveDashboard(private val accounts: AccountRepository, private val catalog: CatalogRepository) {
    operator fun invoke(sortMode: SortMode): Flow<List<DashboardEntry>> =
        accounts.observeAccounts().map { list -> sort(list.mapNotNull { a -> catalog.program(a.programId)?.let { DashboardEntry(it, a) } }, sortMode) }

    companion object {
        fun sort(entries: List<DashboardEntry>, sortMode: SortMode): List<DashboardEntry> {
            val byName = compareBy<DashboardEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.program.displayName }
            return when (sortMode) {
                SortMode.ALPHABETICAL -> entries.sortedWith(byName)
                SortMode.CUSTOM -> entries.sortedWith(compareBy<DashboardEntry> { it.account.sortOrder }.then(byName))
                // Sections follow their shown names alphabetically (Airlines, Credit Cards, Hotels), then programs by name.
                SortMode.BY_CATEGORY -> entries.sortedWith(compareBy<DashboardEntry> { it.program.category.label.lowercase() }.then(byName))
                SortMode.BY_TYPE -> entries.sortedWith(compareBy<DashboardEntry> { it.program.balanceUnit.typeLabel.lowercase() }.then(byName))
            }
        }
    }
}
