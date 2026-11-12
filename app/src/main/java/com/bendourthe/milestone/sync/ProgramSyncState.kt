package com.bendourthe.milestone.sync

sealed interface ProgramSyncState {
    data object Idle : ProgramSyncState
    data object Syncing : ProgramSyncState

    /** The saved password is being typed into the program's sign-in form. */
    data object SigningIn : ProgramSyncState

    /** Waiting for the program's texted code. */
    data object AwaitingCode : ProgramSyncState
}
