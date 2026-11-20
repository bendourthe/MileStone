package com.bendourthe.milestone.sync.webview

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.credentials.AutoSignInStatus
import com.bendourthe.milestone.data.credentials.CredentialVault
import com.bendourthe.milestone.data.credentials.Credentials
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.ConnectionInventory
import com.bendourthe.milestone.domain.SourceCard
import com.bendourthe.milestone.ui.FakeSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionSessionsTest {
    private lateinit var db: MileStoneDatabase
    private lateinit var accounts: RoomAccountRepository
    private lateinit var cards: RoomCardRepository
    private val calls = mutableListOf<String>()
    private val sessions = FakeSessions()
    private val vault = RecordingVault()

    private fun backend(profiles: Boolean = true, names: List<String> = emptyList()) = object : ProfileBackend {
        override val supportsProfiles = profiles
        override fun destroyLiveWebViews(profileName: String) { calls += "destroy:$profileName" }
        override fun deleteProfile(profileName: String) { calls += "delete:$profileName" }
        override suspend fun clearProfileData(profileName: String) { calls += "clear:$profileName" }
        override fun profileNames() = names
        override suspend fun removeAllCookies() { calls += "cookies" }
    }

    private fun subject(profiles: Boolean = true) = ConnectionSessions(backend(profiles), sessions, vault, cards)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accounts = RoomAccountRepository(db)
        cards = RoomCardRepository(db)
        accounts.addAccount("chase", AccountStatus.CONNECTED, 1)
    }

    @After
    fun tearDown() = db.close()

    private fun source(n: Int) = SourceCard("%064x".format(n), "Synthetic Card $n", "4321")

    @Test
    fun legacyConnectionReusesTheProgramProfileAndSavedSignInIdentifiers() = runTest {
        val legacy = subject().legacy("chase", CardSegment.PERSONAL, 5)
        assertNotNull(legacy)
        assertEquals("chase", legacy!!.connectionId)
        assertEquals(SessionProfiles.profileName("chase"), ConnectionSessions.profileName(legacy.connectionId))
        assertTrue(ConnectionSessions.isLegacy(legacy))
        assertEquals(legacy, subject().legacy("chase", CardSegment.BUSINESS, 9))
        assertEquals(null, subject().legacy("delta", CardSegment.PERSONAL, 5))
    }

    @Test
    fun additionalSignInIsRefusedWithoutIsolatedProfilesAndBounded() = runTest {
        subject().legacy("chase", CardSegment.PERSONAL, 1)
        assertEquals(ConnectionSessions.AddResult.ProfilesUnsupported, subject(profiles = false).add("chase", CardSegment.BUSINESS, 2))
        assertEquals(1, cards.connections("chase").size)

        val ids = (1..3).map { (subject().add("chase", CardSegment.BUSINESS, 3) as ConnectionSessions.AddResult.Added).connection.connectionId }
        assertEquals(listOf("chase_2", "chase_3", "chase_4"), ids)
        assertEquals(ConnectionSessions.AddResult.LimitReached, subject().add("chase", CardSegment.BUSINESS, 4))
        assertEquals(ConnectionSessions.AddResult.NotTracked, subject().add("delta", CardSegment.BUSINESS, 4))
    }

    @Test
    fun signingOutAnAdditionalSignInTouchesOnlyItsProfileSessionSavedSignInAndCards() = runTest {
        val legacy = subject().legacy("chase", CardSegment.PERSONAL, 1)!!
        val extra = (subject().add("chase", CardSegment.BUSINESS, 2) as ConnectionSessions.AddResult.Added).connection
        listOf(legacy.connectionId, extra.connectionId).forEach { sessions.markConnected(it, 3); vault.saved += it }
        cards.applyConnectionInventory(ConnectionInventory("chase", legacy.connectionId, listOf(source(1)), true), 10)
        cards.applyConnectionInventory(ConnectionInventory("chase", extra.connectionId, listOf(source(1), source(2)), true), 20)

        subject().signOutAdditional(extra)

        assertEquals(listOf("destroy:program_chase_2", "clear:program_chase_2", "delete:program_chase_2"), calls)
        assertEquals(setOf("chase"), sessions.records.keys)
        assertEquals(setOf("chase"), vault.saved)
        assertEquals(listOf("chase"), cards.connections("chase").map { it.connectionId })
        val remaining = cards.observeCards("chase").first()
        assertEquals(1, remaining.size)
        assertEquals(CardSegment.PERSONAL, remaining.single().segment)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { subject().signOutAdditional(legacy) } }
    }

    @Test
    fun legacySignOutRemovesOnlyItsExclusiveCardsWhenOtherSignInsRemain() = runTest {
        val legacy = subject().legacy("chase", CardSegment.PERSONAL, 1)!!
        assertFalse(subject().afterLegacySignOut("chase"))
        assertEquals(1, cards.connections("chase").size)

        val extra = (subject().add("chase", CardSegment.BUSINESS, 2) as ConnectionSessions.AddResult.Added).connection
        cards.applyConnectionInventory(ConnectionInventory("chase", legacy.connectionId, listOf(source(1), source(5)), true), 10)
        cards.applyConnectionInventory(ConnectionInventory("chase", extra.connectionId, listOf(source(1)), true), 20)

        assertTrue(subject().afterLegacySignOut("chase"))

        assertEquals(listOf("chase_2"), cards.connections("chase").map { it.connectionId })
        val remaining = cards.observeCards("chase").first()
        assertEquals(1, remaining.size)
        assertEquals(CardSegment.BUSINESS, remaining.single().segment)
    }

    @Test
    fun removingTheProgramSignsOutEveryAdditionalSignIn() = runTest {
        val legacy = subject().legacy("chase", CardSegment.PERSONAL, 1)!!
        val second = (subject().add("chase", CardSegment.BUSINESS, 2) as ConnectionSessions.AddResult.Added).connection
        val third = (subject().add("chase", CardSegment.PERSONAL, 3) as ConnectionSessions.AddResult.Added).connection
        listOf(legacy, second, third).forEach { sessions.markConnected(it.connectionId, 4); vault.saved += it.connectionId }

        subject().signOutAllAdditional("chase")

        // The legacy sign-in is left to the program's own sign-out, which runs next.
        assertEquals(listOf("chase"), cards.connections("chase").map { it.connectionId })
        assertEquals(setOf("chase"), sessions.records.keys)
        assertEquals(setOf("chase"), vault.saved)
        assertEquals(setOf("clear:program_chase_2", "clear:program_chase_3"), calls.filter { it.startsWith("clear:") }.toSet())
    }

    @Test
    fun startupCleanupKeepsAdditionalSignInProfilesAndDeletesUntrackedOnes() = runTest {
        subject().legacy("chase", CardSegment.PERSONAL, 1)
        subject().add("chase", CardSegment.BUSINESS, 2)
        val names = listOf("program_chase", "program_chase_2", "program_chase_3", "program_delta", "Default")
        val profiles = SessionProfiles(backend(names = names), sessions, com.bendourthe.milestone.ui.FakeAccounts().also {
            it.addAccount("chase", AccountStatus.CONNECTED, 0)
        })

        profiles.deleteLeftoverProfiles(subject()::additionalProfiles)

        assertEquals(listOf("delete:program_chase_3", "delete:program_delta"), calls)
    }

    /** Records which saved sign-ins exist; holds no secrets. */
    private class RecordingVault : CredentialVault {
        val saved = mutableSetOf<String>()
        override val statuses: StateFlow<Map<String, AutoSignInStatus>> = MutableStateFlow(emptyMap())
        override suspend fun usable(programId: String): Credentials? = null
        override suspend fun save(programId: String, credentials: Credentials, readTexts: Boolean) { saved += programId }
        override suspend fun delete(programId: String) { saved -= programId }
        override suspend fun block(programId: String) = Unit
        override suspend fun unblock(programId: String) = Unit
    }
}
