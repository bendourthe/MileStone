package com.bendourthe.milestone.ui.search

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.ui.theme.AuroraBackground
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SearchUpdatingStatusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun retainedMatchesAndEmptyStatesShowUpdatingAtNarrowWidthAndLargeFont() {
        assertTrue("This fixture is emulator-only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MileStoneDatabase::class.java).build()
        val accounts = RoomAccountRepository(db)
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val vm = SearchViewModel(accounts, RoomCardRepository(db), StaticCatalogRepository(), worker = worker)
        val job = vm.viewModelScope.coroutineContext[Job]!!
        val store = ViewModelStore().apply { put("updating-search", vm) }
        val gates = mutableListOf<CountDownLatch>()
        fun holdMatching(): CountDownLatch {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).also { gates += it }
            executor.execute { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            assertTrue("Matching worker is held", entered.await(5, TimeUnit.SECONDS))
            return release
        }
        fun waitText(text: String) {
            rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(text).assertIsDisplayed()
        }
        try {
            runBlocking { accounts.addAccount("amex", AccountStatus.CONNECTED, 1) }
            rule.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    MileStoneTheme { AuroraBackground(Modifier.width(320.dp).fillMaxHeight()) {
                        SearchScreen(vm, onBack = {}, onSelect = {})
                    } }
                }
            }
            val query = rule.onNodeWithTag("globalSearchQuery")
            query.performTextInput("Amex")
            query.performImeAction()
            waitText("American Express")
            val first = holdMatching()
            query.performTextClearance()
            query.performTextInput("Amex unavailablefixturetoken")
            rule.onNodeWithText("Updating results").assertIsDisplayed()
            rule.onNodeWithText("American Express").assertIsDisplayed()
            first.countDown()
            waitText("No matches in your stored items")
            rule.onNodeWithText("Updating results").assertDoesNotExist()
            val second = holdMatching()
            query.performTextClearance()
            query.performTextInput("Amex")
            rule.onNodeWithText("Updating results").assertIsDisplayed()
            rule.onNodeWithText("No matches in your stored items").assertDoesNotExist()
            second.countDown()
            waitText("American Express")
            rule.onNodeWithText("Updating results").assertDoesNotExist()
        } finally {
            gates.forEach { it.countDown() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { job.join() }
            worker.close()
            db.close()
        }
    }
}
