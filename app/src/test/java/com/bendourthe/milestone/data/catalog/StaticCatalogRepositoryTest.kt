package com.bendourthe.milestone.data.catalog

import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.sync.webview.HostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticCatalogRepositoryTest {
    private val catalog = StaticCatalogRepository()

    @Test
    fun hasFourteenUniquePrograms() {
        assertEquals(14, catalog.programs.size)
        assertEquals(14, catalog.programs.map { it.programId }.toSet().size)
    }

    @Test
    fun everyUrlBelongsToItsOwnHosts() {
        catalog.programs.forEach { program ->
            assertTrue("${program.programId} loginUrl", HostPolicy.isAllowed(program.loginUrl, program.allowedHosts))
            assertTrue("${program.programId} dashboardUrl", HostPolicy.isAllowed(program.dashboardUrl, program.allowedHosts))
        }
    }

    @Test
    fun capabilitiesMatchTheFeasibilityRecord() {
        val automatic = catalog.programs.filter { it.syncCapability is SyncCapability.Automatic }.map { it.programId }.toSet()
        assertEquals(setOf("amex", "chase", "capitalone", "delta", "united", "alaska", "aeroplan", "marriott", "hyatt", "hilton"), automatic)
    }

    @Test
    fun everyLogoKeyResolvesToABundledDrawable() {
        val withLogo = catalog.programs.filter { it.logoKey != null }
        assertEquals(setOf("bilt", "britishairways"), catalog.programs.map { it.programId }.toSet() - withLogo.map { it.programId }.toSet())
        withLogo.forEach { assertTrue("${it.programId} logo", com.bendourthe.milestone.ui.components.ProgramLogos.resId(it.logoKey) != null) }
    }

    @Test
    fun popularIsThePlannedSix() {
        val popular = catalog.programs.filter { it.isPopular }.map { it.programId }.toSet()
        assertEquals(setOf("amex", "chase", "capitalone", "delta", "united", "marriott"), popular)
    }

    @Test
    fun onlyCapitalOneCountsCashAndFormatsItInDollars() {
        val cash = catalog.programs.filter { it.balanceUnit == com.bendourthe.milestone.domain.BalanceUnit.CASH_CENTS }
        assertEquals(listOf("capitalone"), cash.map { it.programId })
        assertEquals("$1,234.56 cash", cash.single().formatBalance(123_456, java.util.Locale.US))
        assertEquals("142,500 pts", catalog.program("amex")!!.formatBalance(142_500, java.util.Locale.US))
    }

    @Test
    fun dashboardUrlsAreRecognizedAsDashboards() {
        catalog.programs.filter { it.canSync }.forEach { program ->
            val spec = (program.syncCapability as SyncCapability.Automatic).spec as com.bendourthe.milestone.domain.ExtractionSpec.Selector
            assertTrue("${program.programId} loggedIn", spec.loggedInUrlPattern.containsMatchIn(program.dashboardUrl))
            assertTrue("${program.programId} login", !spec.loginUrlPattern.containsMatchIn(program.dashboardUrl))
        }
    }

    @Test
    fun searchAirReturnsTheSixAirlinesSortedByName() {
        val result = catalog.search("air")

        assertEquals(6, result.size)
        assertTrue(result.all { it.category == ProgramCategory.AIRLINE })
        assertEquals(result.map { it.displayName }.sortedBy { it.lowercase() }, result.map { it.displayName })
    }

    @Test
    fun blankAndSymbolQueriesDoNotThrow() {
        assertEquals(14, catalog.search("   ").size)
        assertEquals(0, catalog.search("%%").size)
    }

    @Test
    fun lookupByIdFindsEachProgram() {
        catalog.programs.forEach { assertEquals(it, catalog.program(it.programId)) }
    }
}
