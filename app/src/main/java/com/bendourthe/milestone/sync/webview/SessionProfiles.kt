package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.DiagnosticLog
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The WebView operations sign-out needs, behind a seam so ordering is testable without a WebView. */
interface ProfileBackend {
    val supportsProfiles: Boolean
    fun destroyLiveWebViews(profileName: String)
    fun deleteProfile(profileName: String)
    /** Erases one profile's cookies and site storage, which works while a destroyed WebView still holds the profile. */
    suspend fun clearProfileData(profileName: String)
    fun profileNames(): List<String>
    suspend fun removeAllCookies()
}

class SessionProfiles(
    private val backend: ProfileBackend,
    private val sessions: SessionStore,
    private val accounts: AccountRepository,
) {
    suspend fun signOut(programId: String) {
        if (backend.supportsProfiles) {
            val name = profileName(programId)
            backend.destroyLiveWebViews(name)
            backend.clearProfileData(name)
            if (!tryDelete(name)) DiagnosticLog.i(TAG, "Profile for $programId still in use; erased now, deleted at next start")
            sessions.clear(programId)
            accounts.markNeedsReconnect(programId)
        } else {
            backend.destroyLiveWebViews(SHARED_PROFILE)
            backend.removeAllCookies()
            sessions.clearAll()
            accounts.observeAccounts().first()
                .filter { it.status == AccountStatus.CONNECTED || it.programId == programId }
                .forEach { accounts.markNeedsReconnect(it.programId) }
        }
    }

    /** Deletes the profiles of programs no longer tracked; runs at app start, before any WebView holds them. */
    suspend fun deleteLeftoverProfiles() {
        if (!backend.supportsProfiles) return
        val tracked = accounts.observeAccounts().first().map { profileName(it.programId) }.toSet()
        backend.profileNames().filter { it.startsWith(PREFIX) && it !in tracked }.forEach(::tryDelete)
    }

    private fun tryDelete(name: String): Boolean = try {
        backend.deleteProfile(name)
        true
    } catch (e: IllegalStateException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    companion object {
        private const val TAG = "MileStoneSession"
        private const val PREFIX = "program_"
        const val SHARED_PROFILE = "Default"

        fun profileName(programId: String) = PREFIX + programId
    }
}

/** Real backend: tracks live WebViews per profile so a profile is never deleted while in use. */
class WebViewProfileBackend : ProfileBackend {
    private val live = mutableMapOf<String, MutableSet<WebView>>()

    override val supportsProfiles: Boolean get() = WebViewConfig.supportsProfiles

    fun register(profileName: String, webView: WebView) {
        live.getOrPut(profileName) { mutableSetOf() } += webView
    }

    fun unregister(profileName: String, webView: WebView) {
        live[profileName]?.remove(webView)
    }

    override fun destroyLiveWebViews(profileName: String) {
        live.remove(profileName)?.forEach { it.destroy() }
    }

    override fun deleteProfile(profileName: String) {
        ProfileStore.getInstance().deleteProfile(profileName)
    }

    override suspend fun clearProfileData(profileName: String) {
        val profile = ProfileStore.getInstance().getProfile(profileName) ?: return
        profile.webStorage.deleteAllData()
        suspendCancellableCoroutine { cont -> profile.cookieManager.removeAllCookies { cont.resume(Unit) } }
    }

    override fun profileNames(): List<String> = ProfileStore.getInstance().allProfileNames

    override suspend fun removeAllCookies() = suspendCancellableCoroutine { cont ->
        CookieManager.getInstance().removeAllCookies { cont.resume(Unit) }
    }
}
