package com.bendourthe.milestone.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
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
import com.bendourthe.milestone.ui.theme.AuroraBackground
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the Benefits tab and one card page from made-up cards in an in-memory database and saves them to the
 * app's private `files/benefits-shots/` for visual review (plan 4.3). No real card data is involved.
 */
@RunWith(AndroidJUnit4::class)
class BenefitsScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(app, MileStoneDatabase::class.java).build()
    private val accounts = RoomAccountRepository(db)
    private val cards = RoomCardRepository(db)
    private val now = System.currentTimeMillis()
    private val day = 24 * 60 * 60 * 1000L
    private val platinum = CardKey("amex", "71008")
    private val hotel = CardKey("amex", "42005")
    private val viewModel = BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now })

    @After
    fun tearDown() = db.close()

    private fun seed() = runBlocking {
        accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
        val list = CardList.from(listOf(CardDraft("Example Platinum Card", "71008"), CardDraft("Example Hotel Card", "42005")), true)
        cards.applyCardList("amex", list, now - day)
        cards.applyCardBenefits(
            platinum,
            assignBenefitKeys(
                platinum,
                listOf(
                    BenefitDraft(BenefitSection.SPEND_PROGRESS, "Lounge Guest Access", 477_000, 7_023_000, 7_500_000, null, false),
                    BenefitDraft(BenefitSection.CREDIT_EARNED, "\$50 Shopping Credit", 0, 5_000, 5_000, "Oct - Dec", false),
                    BenefitDraft(BenefitSection.CREDIT_EARNED, "\$15 Ride Credit", 500, 1_000, 1_500, "November", false),
                    BenefitDraft(BenefitSection.COMPLETED, "\$20 Streaming Credit", 2_000, 0, 2_000, "Nov", true),
                    BenefitDraft(BenefitSection.LISTED, "Purchase Protection", null, null, null, null, false, description = "Covers eligible purchases against damage or theft.", enrollment = Enrollment.ENROLLED, issuerKey = "pp"),
                    BenefitDraft(BenefitSection.LISTED, "Airline Fee Credit", null, null, null, null, false, description = "Statement credits for incidental airline fees.", enrollment = Enrollment.ENROLLMENT_REQUIRED, issuerKey = "afc"),
                ),
            ),
            now - day,
        )
        cards.applyCardBenefits(hotel, assignBenefitKeys(hotel, listOf(BenefitDraft(BenefitSection.CREDIT_EARNED, "\$50 Hotel Credit", 0, 5_000, 5_000, "Jan - Mar", false))), now - 12 * day)
        cards.applyCardList("amex", list, now - day)
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(app.filesDir, "benefits-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun capturesTheTabAndACardPage() {
        seed()
        var open by mutableStateOf<CardKey?>(null)
        rule.setContent {
            // The app draws every screen over the aurora (MainActivity), so the shots do too.
            MileStoneTheme {
                AuroraBackground(Modifier.fillMaxSize()) {
                    val key = open
                    if (key == null) BenefitsScreen(viewModel, onOpenCard = { open = it }, programId = "amex") else CardBenefitsScreen(viewModel, key, onBack = { open = null }, onGone = {})
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Example Hotel Card", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        save("benefits-tab")
        rule.runOnIdle { open = platinum }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("cardBenefitsList")).fetchSemanticsNodes().isNotEmpty() }
        save("card-page")
        rule.onNodeWithTag("cardBenefitsList").performScrollToNode(androidx.compose.ui.test.hasTestTag("category_OTHER"))
        rule.onNodeWithTag("category_OTHER").performClick()
        rule.onNodeWithTag("cardBenefitsList").performScrollToNode(androidx.compose.ui.test.hasText("Airline Fee Credit"))
        save("card-page-list")
    }
}
