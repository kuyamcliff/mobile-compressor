package com.kuyamcliff.compressor

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CompressorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container
        c.diagnostics.installCrashHandler { c.prefsState.value?.crashReports ?: true }
        c.notifications.createChannels()
        // Everything below is off the main thread: the UI never waits for it.
        c.appScope.launch(Dispatchers.IO) {
            val prefs = c.preferences.current()
            c.diagnostics.collectPreviousExit(prefs.crashReports)
            c.presets.seedBuiltIns()
            c.capabilities.start()
            launch(Dispatchers.Main) { c.deviceMonitor.start() }
            c.queue.start()
            val active = c.jobs.inFlight().map { it.id }.toSet()
            c.files.cleanupAbandoned(active)
            c.database.sources().prune(System.currentTimeMillis() - 90L * 24 * 3600_000)
        }
    }
}
