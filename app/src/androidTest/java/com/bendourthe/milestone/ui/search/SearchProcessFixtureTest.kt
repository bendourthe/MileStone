package com.bendourthe.milestone.ui.search

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bendourthe.milestone.MileStoneApplication
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.SyncCapability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only seed/verification for the host-driven actual MainActivity process-death exercise. */
@RunWith(AndroidJUnit4::class)
class SearchProcessFixtureTest {
    @Test fun unsupportedProgramFixtureCanBeSeededAndVerifiedWithoutIssuerAccess(): Unit = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Emulator-only fixture" }
        val app = ApplicationProvider.getApplicationContext<MileStoneApplication>()
        val container = app.container
        assertTrue(container.catalog.program("citi")!!.syncCapability is SyncCapability.ManualOnly)
        val mode = InstrumentationRegistry.getArguments().getString("fixtureMode")
        if (mode == "verify") {
            val accounts = container.accounts.observeAccounts().first()
            assertEquals(listOf("citi"), accounts.map { it.programId })
            assertEquals(AccountStatus.UNSUPPORTED, accounts.single().status)
            assertTrue(container.cards.allCardsWithBenefits().isEmpty())
            container.accounts.removeAccount("citi")
        } else {
            assertTrue("A clean emulator application is required", container.accounts.observeAccounts().first().isEmpty())
            assertTrue(container.accounts.addAccount("citi", AccountStatus.UNSUPPORTED, 1))
            try {
                assertEquals("citi", container.accounts.observeAccounts().first().single().programId)
                assertTrue(container.cards.allCardsWithBenefits().isEmpty())
            } finally {
                if (mode != "seed") container.accounts.removeAccount("citi")
            }
        }
    }
}
