package com.kuyamcliff.compressor.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.data.prefs.AppPreferences
import com.kuyamcliff.compressor.data.prefs.HardwareFallback
import com.kuyamcliff.compressor.data.prefs.PerformanceProfile
import com.kuyamcliff.compressor.data.prefs.ThemeMode
import com.kuyamcliff.compressor.data.prefs.UiMode
import com.kuyamcliff.compressor.domain.FileNaming
import com.kuyamcliff.compressor.ui.components.ChoiceChips
import com.kuyamcliff.compressor.ui.components.ConfirmDialog
import com.kuyamcliff.compressor.ui.components.DropdownSetting
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.components.SettingHeader
import com.kuyamcliff.compressor.ui.components.SwitchRow
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    val prefs: StateFlow<AppPreferences?> = c.prefsState
    private val _tempBytes = MutableStateFlow(0L)
    val tempBytes: StateFlow<Long> = _tempBytes.asStateFlow()
    val presets = c.presets.presets

    init { refreshTemp() }

    fun update(f: (AppPreferences) -> AppPreferences) = viewModelScope.launch { c.preferences.update(f) }

    fun setFolder(uri: Uri?) = viewModelScope.launch {
        if (uri != null) c.sourceAccess.persistPermission(uri)
        c.preferences.update { it.copy(outputFolderUri = uri?.toString(), outputFolderLabel = uri?.lastPathSegment?.substringAfterLast(':')) }
    }

    fun refreshTemp() = viewModelScope.launch { _tempBytes.value = withContext(Dispatchers.IO) { c.files.tempBytes() } }

    fun clearTemp() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            c.previews.clearAll()
            c.files.cleanupAbandoned(c.jobs.inFlight().map { it.id }.toSet(), olderThanMs = 0)
        }
        refreshTemp()
    }

    fun resetAll() = viewModelScope.launch {
        c.preferences.reset()
        c.settings.clear()
        c.preferences.update { it.copy(onboardingDone = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onOpen: (String) -> Unit) {
    val p by vm.prefs.collectAsState()
    val temp by vm.tempBytes.collectAsState()
    val presets by vm.presets.collectAsState(initial = emptyList())
    var confirmReset by remember { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) vm.setFolder(it) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }) { pad ->
        val prefs = p ?: return@Scaffold
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(stringResource(R.string.appearance)) {
                    SettingHeader(stringResource(R.string.theme))
                    ChoiceChips(ThemeMode.entries, prefs.themeMode, {
                        stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
                    }, { t -> vm.update { it.copy(themeMode = t) } })
                    SettingHeader(stringResource(R.string.interface_mode), stringResource(R.string.interface_mode_help))
                    ChoiceChips(UiMode.entries, prefs.uiMode, { stringResource(if (it == UiMode.SIMPLE) R.string.tier_basic else R.string.tier_advanced) }, { m -> vm.update { it.copy(uiMode = m) } })
                    SwitchRow(stringResource(R.string.expert_controls), prefs.expertControls, { b -> vm.update { it.copy(expertControls = b) } }, subtitle = stringResource(R.string.expert_controls_desc))
                }
            }
            item {
                SectionCard(stringResource(R.string.defaults)) {
                    DropdownSetting(stringResource(R.string.default_preset), presets.map { it.id }, prefs.defaultPresetId,
                        { id -> presets.firstOrNull { it.id == id }?.name ?: id }, { id -> vm.update { it.copy(defaultPresetId = id) } })
                    SwitchRow(stringResource(R.string.use_last_settings), prefs.useLastSettings, { b -> vm.update { it.copy(useLastSettings = b) } }, subtitle = stringResource(R.string.use_last_settings_desc))
                    SettingHeader(stringResource(R.string.preview_length))
                    ChoiceChips(listOf(5, 10, 20, 30), prefs.previewSeconds, { "${it}s" }, { s -> vm.update { it.copy(previewSeconds = s) } })
                }
            }
            item {
                SectionCard(stringResource(R.string.output)) {
                    LabelValue(stringResource(R.string.output_folder), prefs.outputFolderLabel ?: "Movies/Compressed")
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { folder.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
                        if (prefs.outputFolderUri != null) TextButton(onClick = { vm.setFolder(null) }) { Text(stringResource(R.string.use_default)) }
                    }
                    var tpl by remember(prefs.fileNameTemplate) { mutableStateOf(prefs.fileNameTemplate) }
                    OutlinedTextField(tpl, { t -> tpl = t.take(80); vm.update { it.copy(fileNameTemplate = tpl.ifBlank { "{name}_compressed" }) } },
                        label = { Text(stringResource(R.string.file_name_template)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(FileNaming.tokens.joinToString(" ")) })
                    Text(stringResource(R.string.originals_never_modified), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard(stringResource(R.string.performance)) {
                    SettingHeader(stringResource(R.string.performance_profile), stringResource(R.string.performance_profile_help))
                    ChoiceChips(PerformanceProfile.entries, prefs.performanceProfile, {
                        stringResource(when (it) { PerformanceProfile.PERFORMANCE -> R.string.profile_performance; PerformanceProfile.BALANCED -> R.string.profile_balanced; PerformanceProfile.BATTERY -> R.string.profile_battery; PerformanceProfile.THERMAL -> R.string.profile_thermal })
                    }, { pp -> vm.update { it.copy(performanceProfile = pp) } })
                    SettingHeader(stringResource(R.string.parallel_jobs), stringResource(R.string.parallel_jobs_help))
                    ChoiceChips(listOf(1, 2, 3), prefs.maxParallelSoftware, { it.toString() }, { n -> vm.update { it.copy(maxParallelSoftware = n) } })
                    SwitchRow(stringResource(R.string.charging_only), prefs.chargingOnly, { b -> vm.update { it.copy(chargingOnly = b) } }, subtitle = stringResource(R.string.charging_only_desc))
                    SettingHeader(stringResource(R.string.hw_fallback), stringResource(R.string.hw_fallback_help))
                    ChoiceChips(HardwareFallback.entries, prefs.hardwareFallback, {
                        stringResource(if (it == HardwareFallback.ASK) R.string.fallback_fail else R.string.fallback_software)
                    }, { f -> vm.update { it.copy(hardwareFallback = f) } })
                    SettingHeader(stringResource(R.string.smart_target_iterations))
                    ChoiceChips(listOf(2, 3, 4, 5), prefs.smartTargetIterations, { it.toString() }, { n -> vm.update { it.copy(smartTargetIterations = n) } })
                }
            }
            item {
                SectionCard(stringResource(R.string.storage)) {
                    LabelValue(stringResource(R.string.temporary_files), Format.bytes(temp))
                    TextButton(onClick = vm::clearTemp) { Text(stringResource(R.string.clear_temporary_files)) }
                }
            }
            item {
                SectionCard(stringResource(R.string.diagnostics)) {
                    SwitchRow(stringResource(R.string.local_crash_reports), prefs.crashReports, { b -> vm.update { it.copy(crashReports = b) } }, subtitle = stringResource(R.string.local_crash_reports_desc))
                    DropdownSetting(stringResource(R.string.log_level), listOf(0, 1, 2, 3, 4), prefs.logLevel, { listOf("Errors", "Warnings", "Info", "Verbose", "Debug")[it] }, { l -> vm.update { it.copy(logLevel = l) } })
                    TextButton(onClick = { onOpen("diagnostics") }) { Text(stringResource(R.string.open_diagnostics)) }
                }
            }
            item {
                SectionCard(stringResource(R.string.about)) {
                    listOf("about" to R.string.about_app, "licenses" to R.string.licenses, "privacy" to R.string.privacy, "help" to R.string.help).forEach { (route, label) ->
                        Text(stringResource(label), Modifier.fillMaxWidth().clickable { onOpen(route) }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            item {
                Column {
                    TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.reset_all_settings), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmReset) {
        ConfirmDialog(stringResource(R.string.reset_all_settings), stringResource(R.string.reset_all_settings_text), stringResource(R.string.reset),
            onConfirm = { confirmReset = false; vm.resetAll() }, onDismiss = { confirmReset = false }, destructive = true)
    }
}
