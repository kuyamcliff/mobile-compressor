package com.kuyamcliff.compressor.ui.navigation

import android.net.Uri
import com.kuyamcliff.compressor.model.CompressionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the configure screen was entered (quick actions pre-select a mode). */
enum class EntryMode { NORMAL, MAKE_SMALLER, TARGET_SIZE }

data class ConfigureRequest(
    val uris: List<Uri>,
    val initialConfig: CompressionConfig? = null,
    val presetId: String? = null,
    val entryMode: EntryMode = EntryMode.NORMAL,
    val makeSmallerPercent: Int = 25,
    val targetSizeMb: Double = 0.0,
    val requestId: Long = System.nanoTime(),
)

/** Hand-off between screens (selected files, pending shared intents, deep links). */
class Session {
    private val _configure = MutableStateFlow<ConfigureRequest?>(null)
    val configure: StateFlow<ConfigureRequest?> = _configure.asStateFlow()
    private val _openJob = MutableStateFlow<Long?>(null)
    val openJob: StateFlow<Long?> = _openJob.asStateFlow()

    fun startConfigure(r: ConfigureRequest) { _configure.value = r }
    fun requestOpenJob(id: Long?) { _openJob.value = id }
    fun consumeOpenJob() { _openJob.value = null }
}
