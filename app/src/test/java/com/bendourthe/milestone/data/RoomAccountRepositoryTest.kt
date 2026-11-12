package com.bendourthe.milestone.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BalanceOrigin
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SyncResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAccountRepositoryTest {
    private lateinit var db: MileStoneDatabase
    private lateinit var repo: RoomAccountRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomAccountRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun account(id: String) = repo.observeAccounts().first().single { it.programId == id }

    @Test
    fun insertAppendsAndIgnoresDuplicates() = runTest {
        assertTrue(repo.addAccount("amex", AccountStatus.CONNECTED, 1))
        assertTrue(repo.addAccount("chase", AccountStatus.CONNECTED, 2))
        assertFalse(repo.addAccount("amex", AccountStatus.NEEDS_RECONNECT, 3))
        val accounts = repo.observeAccounts().first()
        assertEquals(listOf("amex" to 0, "chase" to 1), accounts.map { it.programId to it.sortOrder })
        assertEquals(AccountStatus.CONNECTED, account("amex").status)
    }

    @Test
    fun reorderWritesNewSortOrder() = runTest {
        listOf("a", "b", "c").forEach { repo.addAccount(it, AccountStatus.CONNECTED, 1) }
        repo.reorder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), repo.observeAccounts().first().map { it.programId })
        assertEquals(0, account("c").sortOrder)
    }

    @Test
    fun reorderWithWrongIdsWritesNothing() = runTest {
        listOf("a", "b").forEach { repo.addAccount(it, AccountStatus.CONNECTED, 1) }
        val error = runCatching { repo.reorder(listOf("b", "x")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(listOf("a", "b"), repo.observeAccounts().first().map { it.programId })
    }

    @Test
    fun deleteCascadesHistory() = runTest {
        repo.addAccount("hilton", AccountStatus.CONNECTED, 1)
        repo.applySyncResult("hilton", SyncResult.Success(82_410), 10)
        assertEquals(1, repo.observeHistory("hilton").first().size)
        repo.removeAccount("hilton")
        assertTrue(repo.observeAccounts().first().isEmpty())
        assertTrue(repo.observeHistory("hilton").first().isEmpty())
    }

    @Test
    fun createIfMissingAddsConnectedAccountWithResult() = runTest {
        repo.applySyncResult("hilton", SyncResult.Success(82_410), 5)
        assertTrue(repo.observeAccounts().first().isEmpty())
        repo.applySyncResult("hilton", SyncResult.Success(82_410), 5, createIfMissing = true)
        val a = account("hilton")
        assertEquals(82_410L to AccountStatus.CONNECTED, a.balance to a.status)
        assertEquals(1, repo.observeHistory("hilton").first().size)
    }

    @Test
    fun successWritesBalanceStatusAndSnapshot() = runTest {
        repo.addAccount("amex", AccountStatus.CONNECTED, 1)
        repo.applySyncResult("amex", SyncResult.Failure(FailureReason.Timeout), 3)
        repo.applySyncResult("amex", SyncResult.Success(142_500), 5)
        val a = account("amex")
        assertEquals(142_500L, a.balance)
        assertEquals(BalanceOrigin.SCRAPED, a.balanceOrigin)
        assertEquals(AccountStatus.CONNECTED, a.status)
        assertEquals(0, a.consecutiveFailures)
        assertEquals(5L, a.lastAttemptEpochMs)
        assertEquals(5L, a.lastUpdatedEpochMs)
        assertNull(a.lastError)
        assertEquals(listOf(142_500L), repo.observeHistory("amex").first().map { it.balance })
    }

    @Test
    fun failureKeepsBalanceAndCountsFailures() = runTest {
        repo.addAccount("amex", AccountStatus.CONNECTED, 1)
        repo.applySyncResult("amex", SyncResult.Success(100), 2)
        repo.applySyncResult("amex", SyncResult.Failure(FailureReason.Network(503)), 4)
        repo.applySyncResult("amex", SyncResult.Failure(FailureReason.Timeout), 6)
        val a = account("amex")
        assertEquals(100L, a.balance)
        assertEquals(AccountStatus.ERROR, a.status)
        assertEquals(2, a.consecutiveFailures)
        assertEquals(FailureReason.Timeout, a.lastError)
        assertEquals(6L, a.lastAttemptEpochMs)
    }

    @Test
    fun needsReconnectAndUnsupportedKeepBalance() = runTest {
        repo.addAccount("amex", AccountStatus.CONNECTED, 1)
        repo.applySyncResult("amex", SyncResult.Success(100), 2)
        repo.applySyncResult("amex", SyncResult.NeedsReconnect, 4)
        assertEquals(AccountStatus.NEEDS_RECONNECT, account("amex").status)
        assertEquals(100L, account("amex").balance)
        repo.applySyncResult("amex", SyncResult.Unsupported, 6)
        assertEquals(AccountStatus.UNSUPPORTED, account("amex").status)
        assertEquals(100L, account("amex").balance)
        repo.markNeedsReconnect("amex")
        assertEquals(AccountStatus.NEEDS_RECONNECT, account("amex").status)
    }
}
