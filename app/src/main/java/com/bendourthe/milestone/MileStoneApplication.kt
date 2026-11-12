package com.bendourthe.milestone

import android.app.Application
import com.bendourthe.milestone.sync.background.KeepAliveScheduler
import com.bendourthe.milestone.sync.background.Notifications

class MileStoneApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(java.io.File(noBackupFilesDir, "diagnostics.log"))
        container = AppContainer(this)
        Notifications.createChannels(this)
        KeepAliveScheduler.ensureScheduled(this)
    }
}
