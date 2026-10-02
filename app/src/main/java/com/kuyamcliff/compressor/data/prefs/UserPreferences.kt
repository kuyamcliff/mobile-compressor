package com.kuyamcliff.compressor.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UiMode { SIMPLE, ADVANCED }
enum class PerformanceProfile { PERFORMANCE, BALANCED, BATTERY, THERMAL }
enum class HardwareFallback { ASK, SOFTWARE_SAME_CODEC }

@Serializable
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingDone: Boolean = false,
    val uiMode: UiMode = UiMode.SIMPLE,
    val expertControls: Boolean = false,
    val useLastSettings: Boolean = false,
    val defaultPresetId: String = "balanced_1080p",
    val outputFolderUri: String? = null,
    val outputFolderLabel: String? = null,
    val fileNameTemplate: String = "{name}_compressed",
    val maxParallelSoftware: Int = 1,
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
    val chargingOnly: Boolean = false,
    val hardwareFallback: HardwareFallback = HardwareFallback.ASK,
    val smartTargetIterations: Int = 3,
    val logLevel: Int = 2,
    val crashReports: Boolean = true,
    val previewSeconds: Int = 10,
    val benchmarkEnabled: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/** Typed DataStore wrapper. Values are validated/clamped on read. */
class UserPreferences(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val uiMode = stringPreferencesKey("ui_mode")
        val expert = booleanPreferencesKey("expert_controls")
        val useLast = booleanPreferencesKey("use_last_settings")
        val defaultPreset = stringPreferencesKey("default_preset")
        val outputFolder = stringPreferencesKey("output_folder")
        val outputFolderLabel = stringPreferencesKey("output_folder_label")
        val template = stringPreferencesKey("file_name_template")
        val parallel = intPreferencesKey("max_parallel_sw")
        val profile = stringPreferencesKey("performance_profile")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val fallback = stringPreferencesKey("hardware_fallback")
        val iterations = intPreferencesKey("smart_target_iterations")
        val logLevel = intPreferencesKey("log_level")
        val crash = booleanPreferencesKey("crash_reports")
        val preview = intPreferencesKey("preview_seconds")
        val benchmark = booleanPreferencesKey("benchmark_enabled")
    }

    private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E =
        s?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: def

    val flow: Flow<AppPreferences> = context.dataStore.data.map { p ->
        AppPreferences(
            themeMode = enumOf(p[K.theme], ThemeMode.SYSTEM),
            onboardingDone = p[K.onboarding] ?: false,
            uiMode = enumOf(p[K.uiMode], UiMode.SIMPLE),
            expertControls = p[K.expert] ?: false,
            useLastSettings = p[K.useLast] ?: false,
            defaultPresetId = p[K.defaultPreset] ?: "balanced_1080p",
            outputFolderUri = p[K.outputFolder],
            outputFolderLabel = p[K.outputFolderLabel],
            fileNameTemplate = p[K.template] ?: "{name}_compressed",
            maxParallelSoftware = (p[K.parallel] ?: 1).coerceIn(1, 3),
            performanceProfile = enumOf(p[K.profile], PerformanceProfile.BALANCED),
            chargingOnly = p[K.chargingOnly] ?: false,
            hardwareFallback = enumOf(p[K.fallback], HardwareFallback.ASK),
            smartTargetIterations = (p[K.iterations] ?: 3).coerceIn(2, 5),
            logLevel = (p[K.logLevel] ?: 2).coerceIn(0, 4),
            crashReports = p[K.crash] ?: true,
            previewSeconds = (p[K.preview] ?: 10).let { if (it in setOf(5, 10, 15, 30)) it else 10 },
            benchmarkEnabled = p[K.benchmark] ?: false,
        )
    }

    suspend fun current(): AppPreferences = flow.first()

    suspend fun update(block: (AppPreferences) -> AppPreferences) {
        val next = block(current())
        context.dataStore.edit { p ->
            p[K.theme] = next.themeMode.name
            p[K.onboarding] = next.onboardingDone
            p[K.uiMode] = next.uiMode.name
            p[K.expert] = next.expertControls
            p[K.useLast] = next.useLastSettings
            p[K.defaultPreset] = next.defaultPresetId
            if (next.outputFolderUri != null) p[K.outputFolder] = next.outputFolderUri else p.remove(K.outputFolder)
            if (next.outputFolderLabel != null) p[K.outputFolderLabel] = next.outputFolderLabel else p.remove(K.outputFolderLabel)
            p[K.template] = next.fileNameTemplate
            p[K.parallel] = next.maxParallelSoftware.coerceIn(1, 3)
            p[K.profile] = next.performanceProfile.name
            p[K.chargingOnly] = next.chargingOnly
            p[K.fallback] = next.hardwareFallback.name
            p[K.iterations] = next.smartTargetIterations.coerceIn(2, 5)
            p[K.logLevel] = next.logLevel.coerceIn(0, 4)
            p[K.crash] = next.crashReports
            p[K.preview] = next.previewSeconds
            p[K.benchmark] = next.benchmarkEnabled
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
