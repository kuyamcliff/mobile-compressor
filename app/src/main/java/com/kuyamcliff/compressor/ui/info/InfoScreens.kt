package com.kuyamcliff.compressor.ui.info

import android.app.Application
import android.content.Intent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.BuildConfig
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.capability.DeviceCapabilities
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.Pill
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { content(it) }
}

// ---------------------------------------------------------------- diagnostics

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    private val _caps = MutableStateFlow<DeviceCapabilities?>(null)
    val caps: StateFlow<DeviceCapabilities?> = _caps.asStateFlow()
    private val _build = MutableStateFlow<JsonObject?>(null)
    val build: StateFlow<JsonObject?> = _build.asStateFlow()
    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    val device = c.deviceMonitor.state

    init {
        viewModelScope.launch {
            _caps.value = runCatching { c.capabilities.await() }.getOrNull()
            _build.value = runCatching { c.engine.buildInfo() }.onFailure { _engineError.value = it.message }.getOrNull()
        }
    }

    fun rescan() = viewModelScope.launch {
        _scanning.value = true
        _caps.value = runCatching { c.rescanCapabilities() }.getOrNull() ?: _caps.value
        _scanning.value = false
    }

    suspend fun report(): String = withContext(Dispatchers.IO) {
        c.diagnostics.report(_caps.value, _build.value, c.deviceMonitor.state.value, c.files.tempBytes())
    }

    fun crashCount() = c.diagnostics.crashReports().size
    fun clearCrashes() = c.diagnostics.clearCrashReports()
}

@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel, onBack: () -> Unit) {
    val caps by vm.caps.collectAsState()
    val build by vm.build.collectAsState()
    val engineError by vm.engineError.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val device by vm.device.collectAsState()
    val context = LocalContext.current
    var share by remember { mutableStateOf(false) }
    var crashes by remember { mutableStateOf(vm.crashCount()) }
    if (share) LaunchedEffect(Unit) {
        val text = vm.report()
        share = false
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }
    InfoScaffold(stringResource(R.string.diagnostics), onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Notice(NoticeKind.INFO, stringResource(R.string.diagnostics_privacy)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { share = true }) { Text(stringResource(R.string.share_diagnostics)) }
                    OutlinedButton(onClick = vm::rescan, enabled = !scanning) { Text(stringResource(R.string.rescan_codecs)) }
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            item {
                SectionCard(stringResource(R.string.device)) {
                    LabelValue(stringResource(R.string.app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    caps?.let { c ->
                        LabelValue("Android", "${c.androidVersion} (API ${c.sdkInt})")
                        LabelValue(stringResource(R.string.model), "${c.manufacturer} ${c.model}")
                        if (c.socModel.isNotBlank()) LabelValue("SoC", "${c.socManufacturer} ${c.socModel}")
                        LabelValue("CPU", "${c.cpuCores} cores")
                        LabelValue("RAM", com.kuyamcliff.compressor.util.Format.bytes(c.totalRamBytes))
                        if (c.glRenderer.isNotBlank()) LabelValue("GPU", c.glRenderer)
                    }
                    LabelValue(stringResource(R.string.thermal), device.thermalStatus.toString())
                    LabelValue(stringResource(R.string.battery), "${device.batteryPercent}%" + if (device.charging) " ⚡" else "")
                }
            }
            item {
                SectionCard(stringResource(R.string.native_engine)) {
                    if (engineError != null) Notice(NoticeKind.ERROR, engineError!!, title = stringResource(R.string.engine_failed_to_load))
                    build?.let { b ->
                        b.entries.filter { it.value is JsonPrimitive }.forEach { (k, v) ->
                            LabelValue(k, v.jsonPrimitive.content, mono = true)
                        }
                        (b["encoders"] ?: b["libraries"])?.let { SelectionContainer { Text(it.toString(), style = MonoStyle) } }
                    }
                }
            }
            caps?.let { c ->
                item { Text(stringResource(R.string.mediacodec_encoders, c.encoders.size), style = MaterialTheme.typography.titleSmall) }
                items(c.encoders.filter { it.mime.startsWith("video/") }) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(e.name, Modifier.weight(1f), style = MonoStyle)
                            Pill(if (e.hardwareAccelerated) "HW" else "SW")
                            if (e.supports10Bit) Pill("10-bit")
                        }
                        Text("${e.mime} · ${e.maxWidth}×${e.maxHeight} · ${e.rateControls.joinToString("/")}" + if (e.probedModes.isNotEmpty()) " · probed ${e.probedModes.size}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.crash_reports)) {
                    Text(stringResource(R.string.n_crash_reports, crashes))
                    if (crashes > 0) TextButton(onClick = { vm.clearCrashes(); crashes = 0 }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- about etc.

@Composable
fun AboutScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    InfoScaffold(stringResource(R.string.about_app), onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(stringResource(R.string.app_name), subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {
                    Text(stringResource(R.string.about_text))
                }
            }
            item {
                SectionCard(stringResource(R.string.ffmpeg_attribution)) {
                    Text(stringResource(R.string.ffmpeg_attribution_text))
                    TextButton(onClick = { onOpen("licenses") }) { Text(stringResource(R.string.licenses)) }
                }
            }
            item { TextButton(onClick = { onOpen("privacy") }) { Text(stringResource(R.string.privacy)) } }
        }
    }
}

/** Shows the license files bundled in assets/licenses (PRD §168). */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val files by produceState(initialValue = emptyList<String>()) {
        value = withContext(Dispatchers.IO) { context.assets.list("licenses")?.sorted()?.toList() ?: emptyList() }
    }
    var open by remember { mutableStateOf<String?>(null) }
    val text by produceState<String?>(initialValue = null, open) {
        value = open?.let { f -> withContext(Dispatchers.IO) { runCatching { context.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrNull() } }
    }
    InfoScaffold(open ?: stringResource(R.string.licenses), onBack = { if (open != null) open = null else onBack() }) { pad ->
        if (open != null) {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item { SelectionContainer { Text(text ?: "…", style = MonoStyle) } }
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium) }
                items(files) { f ->
                    Text(f, Modifier.fillMaxWidth().clickable { open = f }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun TextPageScreen(title: String, sections: List<Pair<String, String>>, onBack: () -> Unit) {
    InfoScaffold(title, onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sections) { (h, body) -> SectionCard(h) { Text(body) } }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) = TextPageScreen(
    stringResource(R.string.privacy),
    listOf(
        stringResource(R.string.privacy_h1) to stringResource(R.string.privacy_b1),
        stringResource(R.string.privacy_h2) to stringResource(R.string.privacy_b2),
        stringResource(R.string.privacy_h3) to stringResource(R.string.privacy_b3),
        stringResource(R.string.privacy_h4) to stringResource(R.string.privacy_b4),
    ),
    onBack,
)

@Composable
fun HelpScreen(onBack: () -> Unit) = TextPageScreen(
    stringResource(R.string.help),
    listOf(
        stringResource(R.string.help_h1) to stringResource(R.string.help_b1),
        stringResource(R.string.help_h2) to stringResource(R.string.help_b2),
        stringResource(R.string.help_h3) to stringResource(R.string.help_b3),
        stringResource(R.string.help_h4) to stringResource(R.string.help_b4),
        stringResource(R.string.help_h5) to stringResource(R.string.help_b5),
        stringResource(R.string.help_h6) to stringResource(R.string.help_b6),
        stringResource(R.string.help_h7) to stringResource(R.string.help_b7),
    ),
    onBack,
)

// ----------------------------------------------------------------- onboarding

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val pages = listOf(
        R.string.onb_h1 to R.string.onb_b1,
        R.string.onb_h2 to R.string.onb_b2,
        R.string.onb_h3 to R.string.onb_b3,
    )
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(stringResource(pages[page].first), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(pages[page].second), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.skip)) }
                Row(Modifier.weight(1f)) {}
                Button(onClick = { if (page < pages.lastIndex) page++ else onDone() }) {
                    Text(stringResource(if (page < pages.lastIndex) R.string.next else R.string.get_started))
                }
            }
        }
    }
}
