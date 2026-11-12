package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.data.credentials.CredentialVault
import com.bendourthe.milestone.data.credentials.Credentials
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.sync.mfa.OneTimeCodeInbox

/** What a bridge needs to sign in for the user; the password is read only when a sign-in form appears. */
class AutoSignInSupport(
    val plan: AutoSignInPlan,
    val credentials: suspend () -> Credentials?,
    val awaitCode: suspend (sinceEpochMs: Long) -> String?,
) {
    /** Tells [onStep] when the saved password is typed and when a texted code is awaited, for the tile's status. */
    fun reporting(onStep: (ProgramSyncState) -> Unit): AutoSignInSupport =
        if (this === NONE) {
            this
        } else {
            AutoSignInSupport(
                plan,
                credentials = { onStep(ProgramSyncState.SigningIn); credentials() },
                awaitCode = { since -> onStep(ProgramSyncState.AwaitingCode); awaitCode(since) },
            )
        }

    companion object {
        val NONE = AutoSignInSupport(AutoSignInPlan.NONE, { null }, { null })
        const val CODE_WAIT_MS = 120_000L

        fun forProgram(program: Program, vault: CredentialVault, inbox: OneTimeCodeInbox): AutoSignInSupport {
            val status = vault.statuses.value[program.programId]
            if (status == null || !status.saved || status.blocked) return NONE
            return AutoSignInSupport(
                AutoSignInPlan(canFill = true, readTexts = status.readTexts),
                credentials = { vault.usable(program.programId) },
                awaitCode = { since -> inbox.await(program.textKeywords, since, CODE_WAIT_MS) },
            )
        }
    }
}
