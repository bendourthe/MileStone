package com.bendourthe.milestone.sync

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.data.settings.DataStoreBenefitsDisplaySettings
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardDraft
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.assignBenefitKeys
import com.bendourthe.milestone.ui.benefits.BenefitsScreen
import com.bendourthe.milestone.ui.benefits.BenefitsViewModel
import com.bendourthe.milestone.ui.benefits.CardBenefitsScreen
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android/Compose/Room/DataStore exercise. All accounts, digits, amounts, and benefits here are synthetic. */
@RunWith(AndroidJUnit4::class)
class BenefitsRedesignDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: MileStoneDatabase
    private lateinit var cards: RoomCardRepository
    private lateinit var display: DataStoreBenefitsDisplaySettings
    private var readTime = 0L
    private lateinit var viewModel: BenefitsViewModel
    private lateinit var models: ViewModelStore
    private lateinit var dataJob: kotlinx.coroutines.Job
    private val selected = mutableStateOf<CardKey?>(null)
    private val fontScale = mutableStateOf(1f)
    private val captureDirectory = "Pictures/MileStoneSyntheticProof/${UUID.randomUUID()}/"
    private val drafts = listOf(
        CardDraft("Platinum Card", "1234"), CardDraft("Business Platinum Card", "2345"),
        CardDraft("Hilton Honors Surpass Card", "3456"), CardDraft("Gold Delta SkyMiles", "4567"),
    )
    private val resources = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            database = Room.inMemoryDatabaseBuilder(context, MileStoneDatabase::class.java).build()
            cards = RoomCardRepository(database)
            val accounts = RoomAccountRepository(database)
            val now = System.currentTimeMillis()
            readTime = now
            runBlocking {
                accounts.addAccount("amex", AccountStatus.CONNECTED, now)
                cards.applyCardList("amex", CardList.from(drafts, true), now)
                drafts.forEach { draft ->
                    val key = CardKey("amex", draft.lastDigits)
                    cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(
                        BenefitDraft(BenefitSection.CREDIT_EARNED, "\$50 Shopping Credit", 0, 5_000, 5_000, "Current period", false),
                        BenefitDraft(BenefitSection.COMPLETED, "Alpha Credit", 2_000, 0, 2_000, "Current period", true),
                        BenefitDraft(BenefitSection.SPEND_PROGRESS, "Lounge access", 10_000, 90_000, 100_000, null, false),
                        BenefitDraft(BenefitSection.OTHER, "Mystery Benefit", null, null, null, null, false),
                        BenefitDraft(BenefitSection.LISTED, "Shopping Credit", null, null, null, null, false,
                            description = "Sample listed description", enrollment = Enrollment.ENROLLMENT_REQUIRED),
                        BenefitDraft(BenefitSection.LISTED, "Purchase Protection", null, null, null, null, false,
                            description = "Sample protection description", enrollment = Enrollment.ENROLLED),
                    )), now)
                }
            }
            dataJob = SupervisorJob()
            display = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = CoroutineScope(dataJob + Dispatchers.IO)) {
                File(context.cacheDir, "redesign-${UUID.randomUUID()}.preferences_pb")
            })
            runBlocking {
                cards.observeCards("amex").first().forEach { card -> display.recordReadOutcome(card.key, now, card.benefitsReadGeneration, BenefitsRead.ALL_SECTIONS) }
            }
            models = ViewModelStore()
            viewModel = ViewModelProvider(models, viewModelFactory {
                initializer { BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now }, displaySettings = display) }
            })[BenefitsViewModel::class.java]
            selected.value = null
        }

        override fun after() {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            runBlocking { dataJob.cancelAndJoin() }
            database.close()
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, captureDirectory)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun showScreens() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale.value)) {
            MileStoneTheme {
                com.bendourthe.milestone.ui.theme.AuroraBackground(androidx.compose.ui.Modifier.fillMaxSize()) {
                val key = selected.value
                if (key == null) BenefitsScreen(viewModel, onOpenCard = { selected.value = it })
                else CardBenefitsScreen(viewModel, key, onBack = { selected.value = null }, onGone = { selected.value = null })
            }
        }
        }
        }
    }

    @Test
    fun productImagesCategoriesControlsAndSavedNavigationStateWorkOnAndroid() {
        showScreens()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("benefitsList").fetchSemanticsNodes().isNotEmpty() }
        capture("overview")
        drafts.forEachIndexed { index, draft ->
            val tile = hasContentDescription("card ending ${draft.lastDigits}", substring = true)
            compose.onNodeWithTag("benefitsList").performScrollToNode(tile)
            compose.onNode(tile).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("cardBenefitsList").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("cardBenefitsList")
            list.performScrollToNode(hasTestTag("cardArtwork"))
            compose.onNodeWithTag("cardArtwork").assertExists()
            capture("card-$index")
            list.performScrollToNode(hasTestTag("benefitsCompletedLast"))
            if (index == 0) {
                compose.onNodeWithTag("benefitsCompletedLast").performClick()
                compose.waitUntil(5_000) { compose.onNodeWithTag("benefitsCompletedLast").fetchSemanticsNode().config[SemanticsProperties.Selected] }
                compose.onNodeWithTag("benefitsCompletedLast").assertIsSelected()
                list.performScrollToNode(hasTestTag("category_OTHER"))
                compose.onNodeWithTag("category_OTHER").performClick()
                compose.waitUntil(5_000) { compose.onNodeWithTag("category_OTHER").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Expanded" }
                list.performScrollToNode(hasTestTag("category_OTHER"))
                compose.onNodeWithTag("category_OTHER").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
                capture("expanded-other")
                list.performScrollToNode(hasTestTag("category_CREDITS"))
                compose.onNodeWithTag("category_CREDITS").performClick()
                compose.waitUntil(5_000) { compose.onNodeWithTag("category_CREDITS").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Collapsed" }
                compose.onNodeWithTag("category_CREDITS").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            } else {
                list.performScrollToNode(hasTestTag("category_OTHER"))
                compose.onNodeWithTag("category_OTHER").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            }
            compose.onNode(hasContentDescription("Back to Benefits")).performClick()
        }
        val first = hasContentDescription("card ending ${drafts.first().lastDigits}", substring = true)
        compose.onNodeWithTag("benefitsList").performScrollToNode(first)
        compose.onNode(first).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cardBenefitsList").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("cardBenefitsList")
        list.performScrollToNode(hasTestTag("benefitsCompletedLast"))
        compose.onNodeWithTag("benefitsCompletedLast").assertIsSelected()
        list.performScrollToNode(hasTestTag("category_OTHER"))
        compose.onNodeWithTag("category_OTHER").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Mystery Benefit").assertExists()
        compose.runOnIdle { fontScale.value = 2f; selected.value = null }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("benefitsList").fetchSemanticsNodes().isNotEmpty() }
        capture("overview-large-font")
        compose.onNodeWithTag("benefitsList").performScrollToNode(first)
        compose.onNode(first).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cardBenefitsList").fetchSemanticsNodes().isNotEmpty() }
        capture("card-large-font")
        list.performScrollToNode(hasTestTag("category_CREDITS"))
        compose.onNodeWithTag("category_CREDITS").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("category_CREDITS").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Expanded" }
        list.performScrollToNode(hasTestTag("welcomeSetup"))
        compose.onNodeWithTag("welcomeSetup").performClick()
        compose.onNodeWithText("Cancel").performClick()
    }
    @Test
    fun yellowAlertsAcknowledgeAndReturnOnlyAfterRelevantSuccessfulSectionRefresh() {
        val key = CardKey("amex", drafts.first().lastDigits)
        val first = hasContentDescription("card ending ${key.lastDigits}", substring = true)
        fun waitForCount(count: Int) {
            compose.waitUntil(5_000) { runBlocking { viewModel.card(key).first()!!.alerts.size == count } }
            compose.waitForIdle()
        }
        fun openFirst() {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("benefitsList").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("benefitsList").performScrollToNode(first)
            compose.onNode(first).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("cardBenefitsList").fetchSemanticsNodes().isNotEmpty() }
        }
        fun refresh(sections: Set<BenefitSection>) = runBlocking {
            val rows = cards.observeBenefits(key).first().filter { it.section in sections }
            cards.applyCardBenefits(key, rows, readTime, sections)
            val card = cards.observeCards("amex").first().first { it.key == key }
            display.recordReadOutcome(key, readTime, card.benefitsReadGeneration, sections)
        }
        showScreens()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("benefitsList").fetchSemanticsNodes().isNotEmpty() }
        capture("alerts-overview")
        openFirst()
        compose.onNodeWithTag("benefitsAlerts").assertExists()
        capture("alerts-top")
        compose.onNodeWithText("Acknowledge").performClick()
        waitForCount(0)
        compose.onNodeWithTag("benefitsAlerts").assertDoesNotExist()
        capture("alerts-acknowledged")
        compose.onNode(hasContentDescription("Back to Benefits")).performClick()
        openFirst()
        compose.onNodeWithTag("benefitsAlerts").assertDoesNotExist()
        org.junit.Assert.assertEquals(1, runBlocking { viewModel.card(CardKey("amex", drafts[1].lastDigits)).first()!!.alerts.size })
        refresh(BenefitsRead.TRACKER_SECTIONS)
        waitForCount(1)
        compose.onNodeWithTag("cardBenefitsList").performScrollToNode(hasTestTag("ack:listed-refresh"))
        compose.onNodeWithTag("ack:listed-refresh").performClick()
        waitForCount(0)
        refresh(BenefitsRead.TRACKER_SECTIONS)
        waitForCount(0)
        compose.onNodeWithTag("benefitsAlerts").assertDoesNotExist()
        refresh(BenefitsRead.LIST_SECTIONS)
        waitForCount(1)
        compose.onNodeWithTag("cardBenefitsList").performScrollToNode(hasTestTag("benefitsAlerts"))
        compose.onNodeWithText("Enrollment required: $50 Shopping Credit").assertExists()
        capture("alerts-after-successful-refresh")
        compose.runOnIdle { fontScale.value = 2f }
        compose.onNodeWithTag("cardBenefitsList").performScrollToNode(hasTestTag("benefitsAlerts"))
        capture("alerts-large-font")
        org.junit.Assert.assertEquals(6, runBlocking { cards.observeBenefits(key).first().size })
    }

    @Test
    fun failedAlertSaveShowsVisibleFeedbackAndKeepsTheAlertOnAndroid() {
        val key = CardKey("amex", drafts.first().lastDigits)
        val settings = object : com.bendourthe.milestone.domain.BenefitsDisplaySettings by display {
            override suspend fun acknowledge(card: CardKey, alertId: String, readGeneration: Long) { throw java.io.IOException("Synthetic unavailable settings") }
        }
        compose.runOnUiThread {
            val accounts = RoomAccountRepository(database)
            viewModel = ViewModelProvider(models, viewModelFactory {
                initializer { BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { readTime }, displaySettings = settings) }
            })["failing", BenefitsViewModel::class.java]
            selected.value = key
        }
        showScreens()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("benefitsAlerts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Acknowledge").performClick()
        compose.waitUntil(5_000) { runBlocking { viewModel.card(key).first()!!.settingsError != null } }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save this change. Please try again.").assertIsDisplayed()
        compose.onNodeWithTag("benefitsAlerts").assertExists()
        capture("alerts-save-failure")
    }

}
