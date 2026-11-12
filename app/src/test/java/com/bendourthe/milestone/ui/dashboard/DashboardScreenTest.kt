package com.bendourthe.milestone.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeSettings
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val automatic = SyncCapability.Automatic(ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/account"), Regex("/login")))
    private val catalog = FakeCatalog(
        listOf(
            testProgram("amex", "American Express", ProgramCategory.CREDIT_CARD, automatic),
            testProgram("hilton", "Hilton Honors", ProgramCategory.HOTEL, automatic),
        ),
    )
    private val accounts = FakeAccounts().apply {
        accounts.value = listOf(
            LoyaltyAccount("amex", 120_000, null, 1, 1, 0, 0, AccountStatus.CONNECTED, null),
            LoyaltyAccount("hilton", 80_000, null, 1, 1, 1, 1, AccountStatus.ERROR, FailureReason.Timeout),
        )
    }
    private val sync = MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap())
    private var refreshRequests = 0
    private val signIns = mutableListOf<String>()

    private fun show() {
        val settings = FakeSettings()
        val vm = DashboardViewModel(catalog, ObserveDashboard(accounts, catalog), settings, accounts, sync, requestRefreshAll = { refreshRequests++ })
        rule.setContent { MileStoneTheme { DashboardScreen(vm, onAddProgram = {}, onSignIn = { signIns += it }) } }
    }

    @Test
    fun syncingTileSaysWhatItIsDoingAndRefreshAllRequestsRefresh() {
        show()
        rule.onNodeWithContentDescription("Refresh All").performClick()
        assertEquals(1, refreshRequests)
        sync.value = mapOf("amex" to ProgramSyncState.Syncing, "hilton" to ProgramSyncState.SigningIn)
        rule.waitForIdle()
        rule.onNodeWithText("Syncing...").assertIsDisplayed()
        rule.onNodeWithText("Signing in...").assertIsDisplayed()
        rule.onNodeWithText("Timed out").assertDoesNotExist()
        sync.value = emptyMap()
        rule.waitForIdle()
        rule.onNodeWithText("Syncing...").assertDoesNotExist()
        rule.onNodeWithText("Reconnect").assertDoesNotExist()
    }

    @Test
    fun pullingDownAsksBeforeRefreshingEverything() {
        show()
        val pull = { rule.onNodeWithTag("dashboardList").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) } }
        pull()
        rule.onNodeWithText("Refresh all programs?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, refreshRequests)
        rule.onNodeWithText("Refresh all programs?").assertDoesNotExist()
        pull()
        rule.onNodeWithText("Refresh all").performClick()
        assertEquals(1, refreshRequests)
    }

    @Test
    fun signedOutTileShowsSignInNeededInsteadOfAReconnectButton() {
        accounts.accounts.value = listOf(LoyaltyAccount("amex", 120_000, null, 1, 1, 0, 0, AccountStatus.NEEDS_RECONNECT, null))
        show()
        rule.onNodeWithText("Reconnect").assertDoesNotExist()
        rule.onNodeWithText("Sign-in needed").assertIsDisplayed().performClick()
        assertEquals(listOf("amex"), signIns)
    }

    @Test
    fun errorChipOpensSignInAndLongPressExplains() {
        show()
        rule.onNodeWithText("Timed out").assertIsDisplayed().performClick()
        assertEquals(listOf("hilton"), signIns)
        rule.onNodeWithText("Timed out").performTouchInput { longClick() }
        rule.onNodeWithText("The balance did not appear in time.", substring = true).assertIsDisplayed()
    }
}
