package com.bendourthe.milestone.ui.dashboard

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BalanceOrigin
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeSettings
import com.bendourthe.milestone.ui.theme.AuroraBackground
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the dashboard with made-up balances and saves it to build/outputs/screenshots for the release docs. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersTheDashboardWithSyntheticBalances() {
        val now = System.currentTimeMillis()
        fun account(id: String, balance: Long?, order: Int, status: AccountStatus = AccountStatus.CONNECTED) =
            LoyaltyAccount(id, balance, balance?.let { BalanceOrigin.SCRAPED }, balance?.let { now - 3_600_000L * order }, now, 0, order, status, null)
        val catalog = StaticCatalogRepository()
        val accounts = FakeAccounts().apply {
            accounts.value = listOf(
                account("amex", 123_450, 0),
                account("chase", 67_890, 1),
                account("capitalone", 45_600, 2),
                account("aeroplan", 21_000, 3),
                account("alaska", null, 4, AccountStatus.NEEDS_RECONNECT),
                account("hilton", 128_400, 5),
                account("hyatt", 42_500, 6),
            )
        }
        val sync = MutableStateFlow(mapOf<String, ProgramSyncState>("hyatt" to ProgramSyncState.Syncing))
        val vm = DashboardViewModel(catalog, ObserveDashboard(accounts, catalog), FakeSettings(), accounts, sync, requestRefreshAll = {})
        rule.setContent { MileStoneTheme { AuroraBackground(Modifier.fillMaxSize()) { DashboardScreen(vm, onAddProgram = {}) } } }

        rule.onNodeWithText("American Express").assertIsDisplayed()
        val out = File("build/outputs/screenshots/dashboard.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
