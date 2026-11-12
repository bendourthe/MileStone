package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.data.session.FakeAead
import com.bendourthe.milestone.data.session.TinkSessionStore
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.ui.FakeAccounts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionProfilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val accounts = FakeAccounts()

    private fun backend(profiles: Boolean, failDelete: Boolean = false, names: List<String> = emptyList()) = object : ProfileBackend {
        override val supportsProfiles = profiles
        override fun destroyLiveWebViews(profileName: String) { calls += "destroy:$profileName" }
        override fun deleteProfile(profileName: String) {
            calls += "delete:$profileName"
            if (failDelete) throw IllegalStateException("in use")
        }
        override suspend fun clearProfileData(profileName: String) { calls += "clear:$profileName" }
        override fun profileNames() = names
        override suspend fun removeAllCookies() { calls += "cookies" }
    }

    private fun TestScope.sessions() = TinkSessionStore(tmp.root.resolve("s.pb"), FakeAead(), backgroundScope) {}

    @Test
    fun perProgramSignOutDestroysThenDeletesThenMarks() = runTest(UnconfinedTestDispatcher()) {
        val sessions = sessions()
        listOf("amex", "chase").forEach { accounts.addAccount(it, AccountStatus.CONNECTED, 0); sessions.markConnected(it, 1) }
        SessionProfiles(backend(profiles = true), sessions, accounts).signOut("amex")
        assertEquals(listOf("destroy:program_amex", "clear:program_amex", "delete:program_amex"), calls)
        assertNull(sessions.get("amex"))
        assertEquals(1L, sessions.get("chase")?.connectedAtEpochMs)
        assertEquals(
            mapOf("amex" to AccountStatus.NEEDS_RECONNECT, "chase" to AccountStatus.CONNECTED),
            accounts.accounts.value.associate { it.programId to it.status },
        )
    }

    @Test
    fun failedProfileDeleteStillMarksReconnect() = runTest(UnconfinedTestDispatcher()) {
        val sessions = sessions()
        accounts.addAccount("amex", AccountStatus.CONNECTED, 0)
        SessionProfiles(backend(profiles = true, failDelete = true), sessions, accounts).signOut("amex")
        assertEquals(AccountStatus.NEEDS_RECONNECT, accounts.accounts.value.single().status)
        assertEquals("a profile still in use is erased even though it can't be deleted yet", "clear:program_amex", calls[1])
    }

    @Test
    fun startDeletesOnlyProfilesOfRemovedPrograms() = runTest(UnconfinedTestDispatcher()) {
        accounts.addAccount("amex", AccountStatus.CONNECTED, 0)
        val names = listOf("program_amex", "program_hyatt", "Default", "site_check_delta")
        SessionProfiles(backend(profiles = true, names = names), sessions(), accounts).deleteLeftoverProfiles()
        assertEquals(listOf("delete:program_hyatt"), calls)
    }

    @Test
    fun sharedProfileSignOutClearsEverything() = runTest(UnconfinedTestDispatcher()) {
        val sessions = sessions()
        accounts.addAccount("amex", AccountStatus.CONNECTED, 0)
        accounts.addAccount("chase", AccountStatus.CONNECTED, 0)
        accounts.addAccount("hilton", AccountStatus.UNSUPPORTED, 0)
        listOf("amex", "chase").forEach { sessions.markConnected(it, 1) }
        SessionProfiles(backend(profiles = false), sessions, accounts).signOut("amex")
        assertEquals(listOf("destroy:Default", "cookies"), calls)
        assertEquals(emptyMap<String, Any>(), sessions.all())
        assertEquals(
            mapOf("amex" to AccountStatus.NEEDS_RECONNECT, "chase" to AccountStatus.NEEDS_RECONNECT, "hilton" to AccountStatus.UNSUPPORTED),
            accounts.accounts.value.associate { it.programId to it.status },
        )
    }
}
