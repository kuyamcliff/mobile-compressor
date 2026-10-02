package com.kuyamcliff.compressor.ui.presets

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.domain.Preset
import com.kuyamcliff.compressor.domain.PresetCategory
import com.kuyamcliff.compressor.ui.Labels
import com.kuyamcliff.compressor.ui.components.ConfirmDialog
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PresetsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    val presets: StateFlow<List<Preset>> = c.presets.presets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val defaultId: StateFlow<String> = c.preferences.flow.map { it.defaultPresetId }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun rename(id: String, name: String) = viewModelScope.launch { c.presets.rename(id, name) }
    fun duplicate(id: String) = viewModelScope.launch { c.presets.duplicate(id) }
    fun delete(id: String) = viewModelScope.launch {
        c.presets.delete(id)
        if (c.preferences.current().defaultPresetId == id) c.preferences.update { it.copy(defaultPresetId = "balanced_1080p") }
    }
    fun setDefault(id: String) = viewModelScope.launch { c.preferences.update { it.copy(defaultPresetId = id) } }
    fun edit(p: Preset, name: String, description: String) = viewModelScope.launch { c.presets.saveCustom(name, description, p.config, id = p.id) }

    fun export(uri: Uri, one: Preset?) = viewModelScope.launch {
        val app = getApplication<Application>()
        val text = if (one != null) c.presets.exportOne(one) else c.presets.exportCustom()
        val ok = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
        }
        _message.value = app.getString(if (ok) R.string.presets_exported else R.string.presets_export_failed)
    }

    fun import(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val text = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 2_000_000 }?.toString(Charsets.UTF_8) }
            }.getOrNull()
        }
        _message.value = if (text == null) app.getString(R.string.presets_import_failed, "unreadable")
        else runCatching { c.presets.import(text) }.fold(
            { app.getString(R.string.presets_imported, it) },
            { app.getString(R.string.presets_import_failed, it.message ?: "invalid") },
        )
    }

    fun dismissMessage() { _message.value = null }
}

/**
 * Preset library (PRD §56–§66). When [onPick] is set (opened from configure),
 * tapping a preset applies it; otherwise it opens details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(vm: PresetsViewModel, onBack: (() -> Unit)?, onPick: ((Preset) -> Unit)?) {
    val presets by vm.presets.collectAsState()
    val defaultId by vm.defaultId.collectAsState()
    val message by vm.message.collectAsState()
    var query by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<Preset?>(null) }
    var menu by remember { mutableStateOf(false) }
    var exportOne by remember { mutableStateOf<Preset?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, exportOne)
        exportOne = null
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    val filtered = remember(presets, query) {
        val q = query.trim()
        if (q.isEmpty()) presets else presets.filter { it.name.contains(q, true) || it.description.contains(q, true) || Labels.codecShort(it.config.video.codec).contains(q, true) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (onPick != null) R.string.choose_preset else R.string.presets)) },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_presets)) }, onClick = { menu = false; importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_custom_presets)) }, onClick = { menu = false; exportOne = null; exporter.launch("compressor-presets.json") })
                }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_presets)) }, singleLine = true) }
            PresetCategory.entries.forEach { cat ->
                val group = filtered.filter { it.category == cat }
                if (group.isNotEmpty()) {
                    item(key = "h_$cat") { Text(categoryLabel(cat), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(group, key = { it.id }) { p ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { if (onPick != null) onPick(p) else details = p }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (p.id == defaultId) Pill(stringResource(R.string.default_flag))
                                    if (onPick != null) TextButton(onClick = { details = p }) { Text(stringResource(R.string.details)) }
                                }
                                Text(p.description, style = MaterialTheme.typography.bodySmall)
                                Text(shortSpec(p), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    details?.let { p ->
        PresetDetails(p, isDefault = p.id == defaultId, onDismiss = { details = null }, vm = vm,
            onApply = onPick?.let { pick -> { pick(p); details = null } },
            onExport = { exportOne = p; exporter.launch(p.name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") })
    }
    message?.let { m -> ConfirmDialog(stringResource(R.string.presets), m, stringResource(R.string.ok), onConfirm = vm::dismissMessage, onDismiss = vm::dismissMessage) }
}

@Composable
private fun categoryLabel(c: PresetCategory) = stringResource(
    when (c) {
        PresetCategory.GENERAL -> R.string.cat_general
        PresetCategory.CONTENT -> R.string.cat_content
        PresetCategory.SHARING -> R.string.cat_sharing
        PresetCategory.PROFILE -> R.string.cat_profile
        PresetCategory.CUSTOM -> R.string.cat_custom
    },
)

private fun shortSpec(p: Preset): String {
    val v = p.config.video
    return listOf(
        Labels.container(p.config.container),
        Labels.codecShort(v.codec),
        if (v.resolution.shortSide > 0) "${v.resolution.shortSide}p" else "",
        if (v.fps.value > 0) "≤${v.fps.value.toInt()} fps" else "",
        if (v.targetSizeMb > 0) "${v.targetSizeMb} MB" else "",
        Labels.audioCodec(p.config.audio.codec),
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun PresetDetails(p: Preset, isDefault: Boolean, onDismiss: () -> Unit, vm: PresetsViewModel, onApply: (() -> Unit)?, onExport: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(p.name) }
    var desc by remember { mutableStateOf(p.description) }
    val v = p.config.video
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (renaming) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    OutlinedTextField(desc, { desc = it.take(200) }, label = { Text(stringResource(R.string.description)) })
                } else {
                    Text(p.description, style = MaterialTheme.typography.bodyMedium)
                    LabelValue(stringResource(R.string.container), Labels.container(p.config.container))
                    LabelValue(stringResource(R.string.codec), Labels.codec(v.codec))
                    LabelValue(stringResource(R.string.rate_control), v.rateControl.name.lowercase().replace('_', ' '))
                    LabelValue(stringResource(R.string.resolution), if (v.resolution.shortSide > 0) "${v.resolution.shortSide}p" else stringResource(R.string.same_as_source))
                    LabelValue("FPS", if (v.fps.value > 0) "%.2f".format(v.fps.value) else stringResource(R.string.same_as_source))
                    LabelValue(stringResource(R.string.audio), "${Labels.audioCodec(p.config.audio.codec)} ${p.config.audio.quality.name.lowercase()}")
                    if (p.requirements.tenBit) Text(stringResource(R.string.preset_requires_10bit), style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (!isDefault) TextButton(onClick = { vm.setDefault(p.id) }) { Text(stringResource(R.string.set_default)) }
                        TextButton(onClick = { vm.duplicate(p.id); onDismiss() }) { Text(stringResource(R.string.duplicate)) }
                        TextButton(onClick = onExport) { Text(stringResource(R.string.export)) }
                    }
                    if (!p.builtIn) Row {
                        TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            when {
                renaming -> TextButton(onClick = { vm.edit(p, name, desc); renaming = false; onDismiss() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
                onApply != null -> TextButton(onClick = onApply) { Text(stringResource(R.string.apply)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = { if (renaming || onApply != null) TextButton(onClick = { if (renaming) renaming = false else onDismiss() }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.delete_preset), stringResource(R.string.delete_preset_text, p.name), stringResource(R.string.delete),
            onConfirm = { vm.delete(p.id); confirmDelete = false; onDismiss() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}
