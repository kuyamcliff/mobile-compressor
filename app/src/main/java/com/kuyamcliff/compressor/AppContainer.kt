package com.kuyamcliff.compressor

import android.content.Context
import com.kuyamcliff.compressor.capability.CapabilityResolver
import com.kuyamcliff.compressor.capability.CapabilityScanner
import com.kuyamcliff.compressor.capability.DeviceCapabilities
import com.kuyamcliff.compressor.capability.MediaCodecSizeChecker
import com.kuyamcliff.compressor.data.db.AppDatabase
import com.kuyamcliff.compressor.data.prefs.UserPreferences
import com.kuyamcliff.compressor.data.repo.HistoryRepository
import com.kuyamcliff.compressor.data.repo.JobRepository
import com.kuyamcliff.compressor.data.repo.PresetRepository
import com.kuyamcliff.compressor.data.repo.SettingsRepository
import com.kuyamcliff.compressor.data.repo.SourceRepository
import com.kuyamcliff.compressor.data.storage.AppFiles
import com.kuyamcliff.compressor.data.storage.OutputStorage
import com.kuyamcliff.compressor.data.storage.SourceAccess
import com.kuyamcliff.compressor.diagnostics.Diagnostics
import com.kuyamcliff.compressor.engine.MediaEngine
import com.kuyamcliff.compressor.preview.PreviewManager
import com.kuyamcliff.compressor.queue.DeviceMonitor
import com.kuyamcliff.compressor.queue.JobFactory
import com.kuyamcliff.compressor.queue.Notifications
import com.kuyamcliff.compressor.queue.QueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Manual dependency graph for the process. Everything heavy (native library,
 * codec scan, database) is created lazily so the first frame is never blocked.
 */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(context) }
    val preferences: UserPreferences by lazy { UserPreferences(context) }
    val prefsState: StateFlow<com.kuyamcliff.compressor.data.prefs.AppPreferences?> by lazy {
        preferences.flow.stateIn(appScope, SharingStarted.Eagerly, null)
    }
    val files: AppFiles by lazy { AppFiles(context) }
    val sourceAccess: SourceAccess by lazy { SourceAccess(context) }
    val outputStorage: OutputStorage by lazy { OutputStorage(context) }
    val engine: MediaEngine by lazy { MediaEngine(logLevel = { prefsState.value?.logLevel ?: 2 }) }
    val notifications: Notifications by lazy { Notifications(context) }
    val deviceMonitor: DeviceMonitor by lazy { DeviceMonitor(context) }
    val diagnostics: Diagnostics by lazy { Diagnostics(context, files) }

    val presets by lazy { PresetRepository(database) }
    val jobs by lazy { JobRepository(database) }
    val history by lazy { HistoryRepository(database) }
    val settings by lazy { SettingsRepository(database) }
    val sources by lazy { SourceRepository(context, database, sourceAccess, engine) }

    private val scanner by lazy { CapabilityScanner(context) }

    /** Device capability scan runs once in the background (cached per OS build). */
    val capabilities: Deferred<DeviceCapabilities> by lazy {
        appScope.async(Dispatchers.IO) { scanner.loadOrScan() }
    }

    suspend fun resolver(): CapabilityResolver? = runCatching {
        CapabilityResolver(capabilities.await(), MediaCodecSizeChecker())
    }.getOrNull()

    suspend fun rescanCapabilities(): DeviceCapabilities = scanner.loadOrScan(forceRescan = true)

    val jobFactory: JobFactory by lazy {
        JobFactory({ resolver() }, files, outputStorage, preferences, jobs, history, sources)
    }

    val session = com.kuyamcliff.compressor.ui.navigation.Session()

    val previews: PreviewManager by lazy { PreviewManager(engine, sourceAccess, files, jobFactory) }

    val queue: QueueManager by lazy {
        QueueManager(
            context, appScope, jobs, history, engine, sourceAccess, outputStorage, files, preferences,
            deviceMonitor, notifications, jobFactory.replanner, diagnostics,
        )
    }
}
