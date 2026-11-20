package com.bendourthe.milestone.ui.benefits

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.sync.webview.ConnectionSessions
import com.bendourthe.milestone.sync.webview.ProfileBackend
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeCards
import com.bendourthe.milestone.ui.FakeSessions
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.util.Locale

/** Chase Personal and Business tiles and the sign-ins section (ADR-0009), with synthetic cards only. */
@RunWith(AndroidJUnit4::class)
class ChaseBenefitsTilesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val accounts = FakeAccounts()
    private val cards = FakeCards()
    private val now = 1_800_000_000_000L
    private val viewModel by lazy { BenefitsViewModel(cards, StaticCatalogRepository(), accounts, { now }, ZoneOffset.UTC, Locale.US) }
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MileStoneDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    private fun card(id: String, name: String, digits: String?, segment: CardSegment?, order: Int) =
        Card("chase", digits, name, order, now, now, cardId = id, segment = segment)

    private fun seed() = runBlocking {
        accounts.addAccount("chase", AccountStatus.CONNECTED, 1)
        cards.cards.value = listOf(
            card("c_0000000000000001", "Synthetic Reserve", "1111", CardSegment.PERSONAL, 0),
            card("c_0000000000000002", "Synthetic Ink", "3333", CardSegment.BUSINESS, 1),
            card("c_0000000000000003", "Synthetic Unsorted", "5555", null, 2),
        )
    }

    private fun described(text: String) = rule.onNode(androidx.compose.ui.test.hasContentDescription(text, substring = true))

    private fun show(programId: String?, connections: ConnectionsViewModel? = null) = rule.setContent {
        MileStoneTheme { BenefitsScreen(viewModel, onOpenCard = {}, programId = programId, connections = connections) }
    }

    @Test
    fun chaseShowsSeparatePersonalAndBusinessTiles() {
        seed()
        show(null)
        described("Chase Personal").assertExists()
        described("Chase Business").assertExists()
    }

    @Test
    fun aBusinessTileListsOnlyBusinessCardsWithTheirStoredEnding() {
        seed()
        show("chase#business")
        described("Synthetic Ink, card ending 3333").assertExists()
        described("Synthetic Reserve").assertDoesNotExist()
    }

    @Test
    fun aPersonalTileKeepsUnclassifiedCardsVisibleAndSaysSo() {
        seed()
        show("chase#personal")
        described("Synthetic Reserve, card ending 1111").assertExists()
        described("Synthetic Unsorted, card ending 5555").assertExists()
        described("Needs Personal or Business").assertExists()
        described("Synthetic Ink").assertDoesNotExist()
    }

    @Test
    fun noBusinessTileUntilACardBelongsToIt() {
        runBlocking { accounts.addAccount("chase", AccountStatus.CONNECTED, 1) }
        cards.cards.value = listOf(card("c_0000000000000001", "Synthetic Reserve", "1111", CardSegment.PERSONAL, 0))
        show(null)
        described("Chase Personal").assertExists()
        described("Chase Business").assertDoesNotExist()
    }

    @Test
    fun theSignInsSectionListsTheFirstSignInAndExplainsARefusedSecondOne() {
        seed()
        val repository = RoomCardRepository(db)
        runBlocking { RoomAccountRepository(db).addAccount("chase", AccountStatus.CONNECTED, 1) }
        val noProfiles = object : ProfileBackend {
            override val supportsProfiles = false
            override fun destroyLiveWebViews(profileName: String) = Unit
            override fun deleteProfile(profileName: String) = Unit
            override suspend fun clearProfileData(profileName: String) = Unit
            override fun profileNames() = emptyList<String>()
            override suspend fun removeAllCookies() = Unit
        }
        val sessions = FakeSessions()
        val connections = ConnectionsViewModel(repository, ConnectionSessions(noProfiles, sessions, NoVault, repository), sessions)
        show("chase#business", connections)

        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Sign-in 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("connectionsSection").assertExists()
        rule.onNodeWithText("Sign-in 1 (balance): not signed in").assertExists()
        rule.onNodeWithText("Add a business sign-in").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("can't keep a second sign-in separate", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private object NoVault : com.bendourthe.milestone.data.credentials.CredentialVault {
        override val statuses = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, com.bendourthe.milestone.data.credentials.AutoSignInStatus>())
        override suspend fun usable(programId: String) = null
        override suspend fun save(programId: String, credentials: com.bendourthe.milestone.data.credentials.Credentials, readTexts: Boolean) = Unit
        override suspend fun delete(programId: String) = Unit
        override suspend fun block(programId: String) = Unit
        override suspend fun unblock(programId: String) = Unit
    }
}
