package com.kuyamcliff.compressor.ui.configure

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.domain.QualityRisk
import com.kuyamcliff.compressor.domain.Severity
import com.kuyamcliff.compressor.model.JobPriority
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.preview.PreviewPosition
import com.kuyamcliff.compressor.ui.Labels
import com.kuyamcliff.compressor.ui.components.ChoiceChips
import com.kuyamcliff.compressor.ui.components.ConfirmDialog
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.Pill
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.components.SourcePlayer
import com.kuyamcliff.compressor.ui.navigation.EntryMode
import com.kuyamcliff.compressor.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureScreen(
    vm: ConfigureViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    onOpenCompare: () -> Unit,
    onPickPreset: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val s by vm.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var savePreset by remember { mutableStateOf(false) }
    var showJson by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.edit { it.copy(output = it.output.copy(folderUri = uri.toString())) }
            vm.dismissStart()
        }
    }

    LaunchedEffect(s.start) {
        if (s.start is StartState.Started) {
            onRequestNotificationPermission()
            vm.dismissStart()
            onStarted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = s.search, onValueChange = vm::setSearch, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_settings)) },
                            modifier = Modifier.fillMaxWidth().testTag("settings_search"),
                        )
                    } else {
                        Column {
                            Text(
                                if (s.isBatch) stringResource(R.string.batch_title, s.sources.size) else s.current?.displayName ?: stringResource(R.string.analyzing),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            s.presetName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.setSearch("") }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.search_settings))
                    }
                    IconButton(onClick = vm::undo, enabled = s.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = vm::redo, enabled = s.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.choose_preset)) }, onClick = { menu = false; onPickPreset() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.save_as_preset)) }, onClick = { menu = false; savePreset = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reset_all)) }, onClick = { menu = false; vm.resetAll() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.technical_configuration)) }, onClick = { menu = false; showJson = vm.technicalPlanJson() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.export_configuration)) }, onClick = { menu = false; showJson = vm.exportConfigJson(false) })
                        }
                    }
                },
            )
        },
        bottomBar = { SummaryBar(s, onStart = { vm.requestStart() }) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).testTag("configure_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.isBatch) item { BatchStrip(s, vm) }
            val src = s.sources.getOrNull(s.selected)
            if (src?.error != null) {
                item {
                    Notice(NoticeKind.ERROR, src.error.message.ifBlank { stringResource(R.string.unable_to_read) }, title = stringResource(R.string.unable_to_read))
                }
            }
            if (src?.loading == true) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.analyzing))
                    }
                }
            }
            val file = s.current
            if (file != null && s.search.isBlank()) {
                item {
                    SourcePlayer(Uri.parse(file.uri), file.info.video?.video?.fps ?: 30.0, onPosition = vm::setPlayerPosition)
                }
                item { SourceAnalysisCard(file) }
                if (s.entryMode == EntryMode.MAKE_SMALLER) item { MakeSmallerCard(s, vm) }
                s.recommendation?.let { rec -> item { RecommendationCard(rec, s, vm) } }
                s.smartFps?.let { f ->
                    if (!s.config.video.smartFps && s.config.video.fps == com.kuyamcliff.compressor.model.FpsChoice.SOURCE) item { SmartFpsCard(f, vm) }
                }
            }
            if (file != null) {
                item { IssuesList(s, vm) }
                if (s.search.isBlank()) {
                    item {
                        PrimaryTabRow(selectedTabIndex = s.tier.ordinal) {
                            SettingsTier.entries.forEach { t ->
                                if (t != SettingsTier.EXPERT || s.expertMode) {
                                    Tab(selected = s.tier == t, onClick = { vm.setTier(t) }, text = {
                                        Text(stringResource(when (t) { SettingsTier.BASIC -> R.string.tier_basic; SettingsTier.ADVANCED -> R.string.tier_advanced; SettingsTier.EXPERT -> R.string.tier_expert }))
                                    })
                                }
                            }
                        }
                    }
                }
                settingsSections(s, vm, onPickFolder = { folderPicker.launch(null) })
                if (s.search.isBlank()) item { PreviewCard(s, vm, onOpenCompare) }
                if (s.search.isBlank()) item { PlanDetailsCard(s) }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    when (val st = s.start) {
        is StartState.ConfirmDanger -> AlertDialog(
            onDismissRequest = vm::dismissStart,
            title = { Text(stringResource(R.string.confirm_settings_title)) },
            text = { Text(st.items.joinToString("\n") { "• $it" }) },
            confirmButton = { TextButton(onClick = { vm.dismissStart(); vm.requestStart(confirmedDanger = true) }) { Text(stringResource(R.string.continue_anyway)) } },
            dismissButton = { TextButton(onClick = vm::dismissStart) { Text(stringResource(R.string.cancel)) } },
        )
        is StartState.NameConflict -> AlertDialog(
            onDismissRequest = vm::dismissStart,
            title = { Text(stringResource(R.string.file_exists_title)) },
            text = { Text(stringResource(R.string.file_exists_text, st.name)) },
            confirmButton = {
                Column {
                    TextButton(onClick = { vm.dismissStart(); vm.requestStart(true, com.kuyamcliff.compressor.queue.JobFactory.NameDecision.REPLACE) }) { Text(stringResource(R.string.replace)) }
                    TextButton(onClick = { vm.dismissStart(); vm.requestStart(true, com.kuyamcliff.compressor.queue.JobFactory.NameDecision.RENAME) }) {
                        Text(stringResource(R.string.create_numbered, com.kuyamcliff.compressor.domain.FileNaming.numbered(st.name, 1)))
                    }
                    TextButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.choose_location)) }
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissStart) { Text(stringResource(R.string.cancel)) } },
        )
        is StartState.Calibrating -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.smart_target_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.smart_target_progress, st.step, st.total))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is StartState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissStart,
            title = { Text(stringResource(R.string.cannot_start)) },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = vm::dismissStart) { Text(stringResource(R.string.ok)) } },
        )
        else -> Unit
    }

    if (savePreset) SavePresetDialog(onSave = { n, d -> vm.saveAsPreset(n, d); savePreset = false }, onDismiss = { savePreset = false })
    showJson?.let { text ->
        AlertDialog(
            onDismissRequest = { showJson = null },
            title = { Text(stringResource(R.string.technical_configuration)) },
            text = {
                SelectionContainer {
                    Text(text, style = com.kuyamcliff.compressor.ui.theme.MonoStyle, modifier = Modifier.verticalScroll(rememberScrollState()).height(400.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("config", text))
                    showJson = null
                }) { Text(stringResource(R.string.copy)) }
            },
            dismissButton = { TextButton(onClick = { showJson = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun BatchStrip(s: ConfigureUiState, vm: ConfigureViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(s.sources) { i, src ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (i == s.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.clickable { vm.select(i) },
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        src.thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp), contentScale = ContentScale.Crop) }
                        Column(Modifier.padding(horizontal = 8.dp)) {
                            Text(src.file?.displayName ?: src.uri.lastPathSegment.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.size(width = 140.dp, height = 20.dp))
                            Text(
                                when {
                                    src.loading -> stringResource(R.string.analyzing)
                                    src.error != null -> stringResource(R.string.unable_to_read)
                                    else -> Format.bytes(src.file!!.sizeBytes)
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = { vm.removeSource(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                    }
                }
            }
        }
        com.kuyamcliff.compressor.ui.components.SwitchRow(
            stringResource(R.string.batch_per_file), s.batchPerFile, vm::setBatchPerFile,
            subtitle = stringResource(R.string.batch_per_file_desc),
        )
    }
}

@Composable
private fun MakeSmallerCard(s: ConfigureUiState, vm: ConfigureViewModel) {
    SectionCard(stringResource(R.string.make_smaller), subtitle = stringResource(R.string.make_smaller_question)) {
        ChoiceChips(listOf(10, 25, 50, 75), s.makeSmallerPercent, { stringResource(R.string.percent_of_original, it) }, vm::setMakeSmaller)
        s.current?.let { f ->
            Text(stringResource(R.string.make_smaller_target, Format.bytes(f.sizeBytes * s.makeSmallerPercent / 100)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecommendationCard(rec: com.kuyamcliff.compressor.domain.Recommendation, s: ConfigureUiState, vm: ConfigureViewModel) {
    SectionCard(stringResource(R.string.recommended)) {
        val sum = rec.summary
        Text(
            listOfNotNull(
                Labels.codecShort(rec.config.video.codec),
                sum?.outputSize?.let { "${it.shortSide}p" },
                sum?.let { Format.fpsLabel(it.outputFps) + " FPS" },
                sum?.qualityLabel,
                sum?.audioLabel,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
        )
        sum?.estimate?.let { e ->
            Text(stringResource(R.string.estimated_size, Format.bytesRange(e.lowBytes, e.highBytes)), style = MaterialTheme.typography.bodyMedium)
            if (rec.savingsHigh > 0) Text(stringResource(R.string.estimated_savings, Format.percentRange(rec.savingsLow.coerceAtLeast(0.0), rec.savingsHigh)), style = MaterialTheme.typography.bodyMedium)
        }
        rec.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = vm::applyRecommendation, enabled = rec.config != s.config) { Text(stringResource(R.string.apply_recommendation)) }
        }
    }
}

@Composable
private fun SmartFpsCard(f: com.kuyamcliff.compressor.domain.FpsSuggestion, vm: ConfigureViewModel) {
    Notice(
        NoticeKind.INFO,
        stringResource(R.string.smart_fps_text, Format.fpsLabel(f.fromFps), Format.fpsLabel(f.toFps), Format.percentRange(f.savingLow, f.savingHigh)),
        title = stringResource(R.string.smart_fps),
    ) {
        TextButton(onClick = {
            vm.edit { it.copy(video = it.video.copy(smartFps = true, fps = if (f.toFps >= 59) com.kuyamcliff.compressor.model.FpsChoice.F60 else com.kuyamcliff.compressor.model.FpsChoice.F30, fpsMode = com.kuyamcliff.compressor.model.FpsMode.PEAK)) }
        }) { Text(stringResource(R.string.apply)) }
    }
}

@Composable
private fun IssuesList(s: ConfigureUiState, vm: ConfigureViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Quality guard for target sizes (PRD §63).
        s.resolved?.summary?.feasibility?.let { f ->
            if (f.risk >= QualityRisk.HIGH && s.config.video.rateControl == RateControlMode.TARGET_SIZE) {
                Notice(
                    if (f.risk >= QualityRisk.SEVERE) NoticeKind.ERROR else NoticeKind.WARNING,
                    stringResource(R.string.quality_guard_text, f.reasons.joinToString(", "), Labels.risk(f.risk), Format.bytes(f.recommendedTargetBytes)),
                    title = stringResource(R.string.quality_guard_title),
                ) {
                    TextButton(onClick = {
                        vm.edit { it.copy(video = it.video.copy(targetSizeMb = Math.ceil(f.recommendedTargetBytes / (1024.0 * 1024)))) }
                    }) { Text(stringResource(R.string.use_recommended)) }
                }
                // Smart resolution reduction options (PRD §64).
                Text(stringResource(R.string.resolution_options), style = MaterialTheme.typography.labelLarge)
                f.resolutionOptions.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        vm.edit { c ->
                            c.copy(video = c.video.copy(resolution = com.kuyamcliff.compressor.model.ResolutionChoice.entries.firstOrNull { it.shortSide == o.size.shortSide } ?: com.kuyamcliff.compressor.model.ResolutionChoice.SOURCE))
                        }
                    }.padding(vertical = 6.dp)) {
                        Text(o.label, Modifier.weight(1f))
                        Pill(Labels.risk(o.risk))
                    }
                }
            }
        }
        if (s.optionError != null) Notice(NoticeKind.ERROR, s.optionError, title = stringResource(R.string.invalid_option_title))
        s.issues.forEach { issue ->
            Notice(
                when (issue.severity) { Severity.ERROR -> NoticeKind.ERROR; Severity.WARNING -> NoticeKind.WARNING; Severity.INFO -> NoticeKind.INFO },
                issue.message,
            ) {
                issue.fixes.forEach { fix -> TextButton(onClick = { vm.applyFix(fix) }) { Text(fix.label) } }
            }
        }
    }
}

@Composable
private fun PreviewCard(s: ConfigureUiState, vm: ConfigureViewModel, onOpenCompare: () -> Unit) {
    SectionCard(stringResource(R.string.preview_title), subtitle = stringResource(R.string.preview_subtitle)) {
        ChoiceChips(listOf(5, 10, 15, 30), s.previewSeconds, { stringResource(R.string.seconds_short, it) }, { vm.setPreviewOptions(seconds = it) })
        ChoiceChips(
            PreviewPosition.entries.filter { it != PreviewPosition.CUSTOM }, s.previewPosition,
            { stringResource(when (it) { PreviewPosition.CURRENT -> R.string.pos_current; PreviewPosition.BEGINNING -> R.string.pos_beginning; PreviewPosition.MIDDLE -> R.string.pos_middle; PreviewPosition.END -> R.string.pos_end; PreviewPosition.CUSTOM -> R.string.custom }) },
            { vm.setPreviewOptions(position = it) },
        )
        when (val p = s.preview) {
            PreviewState.Idle -> Button(onClick = vm::runPreview, enabled = s.resolved != null, modifier = Modifier.testTag("preview_button")) { Text(stringResource(R.string.encode_preview)) }
            is PreviewState.Running -> {
                LinearProgressIndicator(progress = { p.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancelPreview) { Text(stringResource(R.string.cancel)) }
            }
            is PreviewState.Done -> {
                val r = p.result
                LabelValue(stringResource(R.string.original_sample), Format.bytes(r.sourceSegmentBytes))
                LabelValue(stringResource(R.string.compressed_sample), Format.bytes(r.sampleBytes))
                r.metrics?.let { m ->
                    LabelValue("PSNR", "%.1f dB".format(m.psnr))
                    LabelValue("SSIM", "%.4f (%.1f dB)".format(m.ssim, m.ssimDb))
                    Text(stringResource(R.string.metrics_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LabelValue(stringResource(R.string.encode_speed), "${Format.fps(r.encodeFps)} FPS · ${Format.speed(r.speed)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenCompare) { Text(stringResource(R.string.compare_side_by_side)) }
                    OutlinedButton(onClick = vm::runPreview) { Text(stringResource(R.string.encode_again)) }
                }
            }
            is PreviewState.Failed -> {
                Notice(NoticeKind.ERROR, p.error.message, title = stringResource(R.string.preview_failed))
                Button(onClick = vm::runPreview) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun PlanDetailsCard(s: ConfigureUiState) {
    val r = s.resolved ?: return
    val sum = r.summary
    SectionCard(stringResource(R.string.execution_plan), subtitle = stringResource(R.string.execution_plan_subtitle)) {
        LabelValue(stringResource(R.string.pipeline), Labels.pipeline(sum.pipeline))
        LabelValue(stringResource(R.string.encoder), sum.encoderDisplay, mono = true)
        sum.hwEncoderComponent?.let { LabelValue(stringResource(R.string.codec_component), it, mono = true) }
        sum.hwDecoderComponent?.let { LabelValue(stringResource(R.string.decoder_component), it, mono = true) }
        sum.inputMode?.let { LabelValue(stringResource(R.string.input), it) }
        r.plan.video?.let { v ->
            if (v.mode == "transcode") {
                LabelValue(stringResource(R.string.bit_depth), "${v.bitDepth}-bit (${v.pixFmt})", mono = true)
                if (v.hwProfile > 0 && sum.hwEncoderComponent != null) {
                    LabelValue(stringResource(R.string.profile), com.kuyamcliff.compressor.capability.CodecProfiles.name(v.mime, v.hwProfile))
                } else if (v.profile.isNotEmpty()) LabelValue(stringResource(R.string.profile), v.profile)
                if (v.preset.isNotEmpty()) LabelValue(stringResource(R.string.encoder_preset), v.preset)
            }
        }
        if (sum.hardwareUnavailableReasons.isNotEmpty() && s.config.video.engine != com.kuyamcliff.compressor.model.EngineChoice.SOFTWARE) {
            Text(stringResource(R.string.why_not_hardware), style = MaterialTheme.typography.labelLarge)
            sum.hardwareUnavailableReasons.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        sum.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(stringResource(R.string.disclaimer_estimates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings summary with estimate and the Start button (PRD §143). */
@Composable
private fun SummaryBar(s: ConfigureUiState, onStart: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val sum = s.resolved?.summary
            if (sum != null) {
                Text(
                    listOfNotNull(
                        if (s.resolved.plan.video?.mode == "copy") stringResource(R.string.fast_remux) else Labels.codecShort(s.config.video.codec),
                        sum.outputSize?.let { "${it.shortSide}p" },
                        sum.outputFps.takeIf { it > 0 }?.let { Format.fpsLabel(it) + " FPS" },
                        sum.qualityLabel.takeIf { it.isNotBlank() },
                        sum.audioLabel,
                        Labels.container(s.config.container),
                        Labels.pipeline(sum.pipeline),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val e = sum?.estimate
                    Text(
                        when {
                            s.planning && sum == null -> stringResource(R.string.calculating)
                            e != null -> stringResource(R.string.estimated_size, Format.bytesRange(e.lowBytes, e.highBytes))
                            s.errors.isNotEmpty() -> stringResource(R.string.fix_issues_first)
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("estimate_text"),
                    )
                    sum?.encodeSeconds?.let { Text(stringResource(R.string.estimated_time, Format.duration((it * 1000).toLong())), style = MaterialTheme.typography.labelSmall) }
                }
                Button(
                    onClick = onStart,
                    enabled = sum != null && s.errors.isEmpty() && s.optionError == null && s.readyCount > 0,
                    modifier = Modifier.testTag("start_button"),
                ) {
                    Text(if (s.isBatch) stringResource(R.string.start_batch, s.readyCount) else stringResource(R.string.start_compression))
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_as_preset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(desc, { desc = it.take(200) }, label = { Text(stringResource(R.string.description)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, desc) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun PriorityChooser(selected: JobPriority, onSelect: (JobPriority) -> Unit) {
    ChoiceChips(JobPriority.entries, selected, {
        stringResource(when (it) { JobPriority.LOW -> R.string.priority_low; JobPriority.NORMAL -> R.string.priority_normal; JobPriority.HIGH -> R.string.priority_high })
    }, onSelect)
}

@Suppress("unused")
@Composable
private fun Divider() = HorizontalDivider()

@Suppress("unused")
private fun unusedConfirm() = ConfirmDialog::class
