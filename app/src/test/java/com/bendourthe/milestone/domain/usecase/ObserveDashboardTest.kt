package com.bendourthe.milestone.domain.usecase

import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BalanceUnit
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.domain.testProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveDashboardTest {
    private fun entry(id: String, name: String, order: Int) =
        DashboardEntry(testProgram(id, name), LoyaltyAccount(id, null, null, null, null, 0, order, AccountStatus.UNSUPPORTED, null))

    private val entries = listOf(entry("b", "beta", 0), entry("a", "Alpha", 2), entry("c", "Gamma", 1), entry("d", "alpha", 1))

    @Test
    fun alphabeticalIgnoresCase() {
        assertEquals(listOf("a", "d", "b", "c"), ObserveDashboard.sort(entries, SortMode.ALPHABETICAL).map { it.program.programId })
    }

    @Test
    fun customUsesSortOrderThenName() {
        assertEquals(listOf("b", "d", "c", "a"), ObserveDashboard.sort(entries, SortMode.CUSTOM).map { it.program.programId })
    }

    @Test
    fun byTypeListsTypesAlphabeticallyThenSortsByName() {
        val cash = DashboardEntry(testProgram("k", "Zulu Cash").copy(balanceUnit = BalanceUnit.CASH_CENTS), entries[0].account.copy(programId = "k", sortOrder = 0))
        // "Cash back" comes before "Points and miles".
        assertEquals(listOf("k", "a", "d", "b", "c"), ObserveDashboard.sort(entries + cash, SortMode.BY_TYPE).map { it.program.programId })
    }

    @Test
    fun byCategoryListsCategoriesAlphabeticallyThenSortsByName() {
        val hotel = DashboardEntry(testProgram("h", "Aardvark Inn").copy(category = ProgramCategory.HOTEL), entries[0].account.copy(programId = "h"))
        val card = DashboardEntry(testProgram("z", "Zeta Card").copy(category = ProgramCategory.CREDIT_CARD), entries[0].account.copy(programId = "z"))
        val airline = DashboardEntry(testProgram("m", "Mu Air").copy(category = ProgramCategory.AIRLINE), entries[0].account.copy(programId = "m"))
        // Airlines, then Credit cards, then Hotels.
        assertEquals(listOf("m", "z", "h"), ObserveDashboard.sort(listOf(hotel, airline, card), SortMode.BY_CATEGORY).map { it.program.programId })
    }
}
