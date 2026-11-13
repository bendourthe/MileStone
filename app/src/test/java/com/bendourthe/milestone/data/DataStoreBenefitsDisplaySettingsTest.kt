package com.bendourthe.milestone.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bendourthe.milestone.data.settings.DataStoreBenefitsDisplaySettings
import com.bendourthe.milestone.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DataStoreBenefitsDisplaySettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val first = CardKey("amex", "1234")
    private val second = CardKey("amex", "5678")

    @Test fun defaultsAndCardScopedChangesSurviveReopeningTheStore() = runTest(UnconfinedTestDispatcher()) {
        val job = SupervisorJob()
        val file = tmp.root.resolve("benefits.preferences_pb")
        val repo = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = CoroutineScope(coroutineContext + job)) { file })
        assertEquals(setOf(BenefitsCategory.OTHER), repo.observe(first).first().collapsed)
        repo.setCompletedLast(first, true)
        repo.setCollapsed(first, BenefitsCategory.OTHER, false)
        repo.setCollapsed(first, BenefitsCategory.CREDITS, true)
        repo.acknowledge(first, "enrollment:offer", 100)
        val welcome = WelcomeBonusSetup(10_000, 100_000, LocalDate.of(2027, 1, 1), "Welcome reward")
        repo.setWelcomeBonus(first, welcome)
        assertEquals(BenefitsDisplayPreferences(), repo.observe(second).first())
        job.cancelAndJoin()
        val reopened = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        val value = reopened.observe(first).first()
        assertTrue(value.completedLast)
        assertEquals(setOf(BenefitsCategory.CREDITS), value.collapsed)
        assertEquals(welcome, value.welcomeBonus)
        assertTrue(value.isAcknowledged("enrollment:offer", 100))
        assertFalse(value.isAcknowledged("enrollment:offer", 101))
        reopened.setWelcomeBonus(first, null)
        assertNull(reopened.observe(first).first().welcomeBonus)
        assertTrue(reopened.observe(first).first().completedLast)
    }

    @Test fun updatingOneAcknowledgementPreservesTheOthersAndAlertIdentityMayContainSeparators() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("ack.preferences_pb") })
        repo.acknowledge(first, "one\u001efield", 100)
        repo.acknowledge(first, "two", 100)
        repo.acknowledge(first, "one\u001efield", 101)
        val value = repo.observe(first).first()
        assertEquals(mapOf("one\u001efield" to 101L, "two" to 100L), value.acknowledged)
        assertFalse(value.isAcknowledged("one\u001efield", 100))
    }
}
