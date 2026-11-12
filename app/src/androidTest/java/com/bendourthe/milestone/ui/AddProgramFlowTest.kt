package com.bendourthe.milestone.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.CatalogSearch
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SettingsRepository
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.ui.catalog.CatalogViewModel
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import com.bendourthe.milestone.ui.settings.SettingsViewModel
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddProgramFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MileStoneDatabase::class.java).build()

    @After
    fun tearDown() = db.close()

    private fun program(id: String, name: String, category: ProgramCategory, popular: Boolean = false, syncs: Boolean = true) = Program(
        programId = id, displayName = name, category = category, monogram = id.take(2).uppercase(), logoKey = null,
        isPopular = popular, brandColorArgb = 0xFF336699, unitLabel = "pts", loginUrl = "https://www.example.com/$id/login",
        dashboardUrl = "https://www.example.com/$id/account", allowedHosts = setOf("example.com"), identityProviderHosts = emptySet(),
        syncCapability = if (syncs) {
            SyncCapability.Automatic(ExtractionSpec.Selector(SelectorStrategy.CSS, "#balance", Regex("/account"), Regex("/login")))
        } else {
            SyncCapability.ManualOnly("test")
        },
    )

    private val catalog = object : CatalogRepository {
        override val programs = listOf(
            program("amex", "American Express Membership Rewards", ProgramCategory.CREDIT_CARD, popular = true),
            program("marriott", "Marriott Bonvoy", ProgramCategory.HOTEL, popular = true, syncs = false),
            program("hyatt", "World of Hyatt", ProgramCategory.HOTEL),
            program("hilton", "Hilton Honors", ProgramCategory.HOTEL),
        )
        override fun program(programId: String) = programs.firstOrNull { it.programId == programId }
        override fun search(query: String) = CatalogSearch.filter(programs, query)
    }

    private val settings = object : SettingsRepository {
        private val state = MutableStateFlow(SortMode.ALPHABETICAL)
        override val sortMode = state
        override suspend fun setSortMode(mode: SortMode) {
            state.value = mode
        }
        override val refreshInterval = MutableStateFlow(com.bendourthe.milestone.domain.RefreshInterval.WEEKLY)
        override suspend fun setRefreshInterval(interval: com.bendourthe.milestone.domain.RefreshInterval) {
            refreshInterval.value = interval
        }
    }

    @Test
    fun catalogShowsProgramsNotAddedByDefaultAndExplainsOnesThatCannotSync() {
        val accounts = RoomAccountRepository(db)
        runBlocking { accounts.addAccount("amex", AccountStatus.CONNECTED, 0) }
        val sync = MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap())
        val dashboard = DashboardViewModel(catalog, ObserveDashboard(accounts, catalog), settings, accounts, sync)
        val catalogViewModel = CatalogViewModel(catalog, accounts)
        val settingsViewModel = SettingsViewModel(settings)
        rule.setContent {
            MileStoneTheme { MileStoneApp(dashboard, catalogViewModel, settingsViewModel, catalog, "0.1.0") }
        }
        rule.onNodeWithContentDescription("Add program").performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("World of Hyatt")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("American Express Membership Rewards").assertDoesNotExist()
        rule.onNodeWithContentDescription("Filter: Not added").performClick()
        rule.onNodeWithText("Added").performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("World of Hyatt")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("American Express Membership Rewards").assertExists()
        rule.onNodeWithContentDescription("Filter: Added").performClick()
        rule.onNodeWithText("All programs").performClick()
        rule.onNodeWithTag("catalogSearch").performTextInput("marr")
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("World of Hyatt")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Marriott Bonvoy").performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("Marriott Bonvoy can't sign in from MileStone yet")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("catalogSearch").performTextClearance()
        rule.onNodeWithTag("catalogSearch").performTextInput("hil")
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("World of Hyatt")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Hilton Honors").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter balance manually").assertDoesNotExist()
    }
}
