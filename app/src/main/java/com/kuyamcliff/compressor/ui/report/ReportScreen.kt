package com.kuyamcliff.compressor.ui.report

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.data.db.CompressionJobEntity
import com.kuyamcliff.compressor.data.repo.AppJson
import com.kuyamcliff.compressor.engine.EngineError
import com.kuyamcliff.compressor.engine.ProgressStats
import com.kuyamcliff.compressor.model.JobStatus
import com.kuyamcliff.compressor.model.JobSummary
import com.kuyamcliff.compressor.ui.Labels
import com.kuyamcliff.compressor.ui.components.ConfirmDialog
import com.kuyamcliff.compressor.ui.components.EmptyState
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.PlayerControls
import com.kuyamcliff.compressor.ui.components.PlayerSurface
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.components.rememberPlayer
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReportViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    private var id: Long = -1
    lateinit var job: StateFlow<CompressionJobEntity?>
        private set

    fun bind(jobId: Long) {
        if (id == jobId) return
        id = jobId
        job = c.jobs.observe(jobId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun retry() = viewModelScope.launch { c.queue.retry(id) }

    fun deleteOutput(j: CompressionJobEntity, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = j.outputUri?.let { c.outputStorage.deleteOutput(Uri.parse(it)) } ?: false
        if (ok) {
            c.history.forJob(j.id)?.let { c.history.markOutputDeleted(it.id) }
            c.jobs.update(j.copy(statusReason = getApplication<Application>().getString(R.string.output_deleted)))
        }
        onDone(ok)
    }
}

/** Full report (PRD §107): before/after, real statistics, validation, actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(vm: ReportViewModel, jobId: Long, onBack: () -> Unit) {
    vm.bind(jobId)
    val job by vm.job.collectAsState()
    val context = LocalContext.current
    var compare by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.report)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { pad ->
        val j = job
        if (j == null) {
            EmptyState(stringResource(R.string.job_not_found), stringResource(R.string.job_not_found_text), Modifier.padding(pad))
            return@Scaffold
        }
        val status = runCatching { JobStatus.valueOf(j.status) }.getOrDefault(JobStatus.FAILED)
        val summary = remember(j.summaryJson) { runCatching { AppJson.json.decodeFromString(JobSummary.serializer(), j.summaryJson) }.getOrDefault(JobSummary()) }
        val stats = remember(j.statisticsJson) { j.statisticsJson?.let { runCatching { AppJson.json.decodeFromString(ProgressStats.serializer(), it) }.getOrNull() } }
        val error = remember(j.errorJson) { j.errorJson?.let { EngineError.parse(it) } }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(j.sourceName, subtitle = Labels.status(status)) {
                    if (status == JobStatus.COMPLETE) {
                        val saved = j.sourceSize - j.outputBytes
                        Text(
                            stringResource(R.string.saved_x, Format.bytes(j.sourceSize), Format.bytes(j.outputBytes), if (j.sourceSize > 0) Format.percent(saved.toDouble() / j.sourceSize) else "—"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (saved < 0) Notice(NoticeKind.WARNING, stringResource(R.string.output_larger))
                    }
                    j.statusReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (error != null) item {
                Notice(NoticeKind.ERROR, error.message, title = stringResource(R.string.what_went_wrong))
                if (error.suggestions.isNotEmpty() || error.detail.isNotBlank()) {
                    SectionCard(stringResource(R.string.error_details)) {
                        error.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        if (error.stage.isNotBlank()) LabelValue(stringResource(R.string.stage), error.stage, mono = true)
                        LabelValue(stringResource(R.string.code), "${error.categoryName} (${error.code})", mono = true)
                        if (error.detail.isNotBlank()) Text(error.detail, style = com.kuyamcliff.compressor.ui.theme.MonoStyle)
                        error.context.forEach { Text(it, style = com.kuyamcliff.compressor.ui.theme.MonoStyle) }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.before_after)) {
                    Row { Text("", Modifier.weight(1f)); Text(stringResource(R.string.original), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(stringResource(R.string.compressed), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge) }
                    Compare3(stringResource(R.string.size), Format.bytes(j.sourceSize), if (j.outputBytes > 0) Format.bytes(j.outputBytes) else "—")
                    Compare3(stringResource(R.string.codec), summary.sourceCodec, summary.outputCodec)
                    Compare3(stringResource(R.string.resolution), summary.sourceResolution, summary.outputResolution)
                    Compare3("FPS", Format.fpsLabel(summary.sourceFps), Format.fpsLabel(summary.outputFps))
                    Compare3(stringResource(R.string.audio), summary.sourceAudio, summary.outputAudio)
                    Compare3("HDR", summary.sourceHdr.uppercase(), "")
                }
            }
            item {
                SectionCard(stringResource(R.string.encoding)) {
                    LabelValue(stringResource(R.string.pipeline), stats?.pipeline?.ifBlank { null } ?: summary.pipeline)
                    LabelValue(stringResource(R.string.encoder), stats?.encoder?.ifBlank { null } ?: summary.encoderDisplay, mono = true)
                    summary.hwEncoder?.let { LabelValue(stringResource(R.string.codec_component), it, mono = true) }
                    LabelValue(stringResource(R.string.rate_control), summary.rateControl)
                    if (summary.quality.isNotBlank()) LabelValue(stringResource(R.string.quality), summary.quality)
                    LabelValue(stringResource(R.string.container), summary.container)
                    if (summary.estimateHigh > 0) LabelValue(stringResource(R.string.estimated_size), Format.bytesRange(summary.estimateLow, summary.estimateHigh))
                    if (summary.targetBytes > 0) LabelValue(stringResource(R.string.target_size), Format.bytes(summary.targetBytes))
                    j.presetName?.let { LabelValue(stringResource(R.string.preset), it) }
                    stats?.let { st ->
                        val wall = if (st.elapsedUs > 0) st.elapsedUs else st.wallTimeUs
                        LabelValue(stringResource(R.string.encode_time), Format.duration(wall / 1000))
                        if (st.averageFps > 0) LabelValue(stringResource(R.string.average_fps), "%.1f".format(st.averageFps))
                        if (st.speed > 0) LabelValue(stringResource(R.string.encode_speed), Format.speed(st.speed))
                        if (st.frames > 0) LabelValue(stringResource(R.string.frames), st.frames.toString())
                        if (st.outputBitrate > 0) LabelValue(stringResource(R.string.output_bitrate), Format.bitrate(st.outputBitrate))
                        if (st.decodeErrors > 0) LabelValue(stringResource(R.string.decode_errors), st.decodeErrors.toString())
                        st.warnings.forEach { Notice(NoticeKind.WARNING, it) }
                    }
                    summary.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (status == JobStatus.COMPLETE && j.outputUri != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { message = open(context, Uri.parse(j.outputUri), j.outputName) }) { Text(stringResource(R.string.open)) }
                        OutlinedButton(onClick = { share(context, Uri.parse(j.outputUri)) }) { Text(stringResource(R.string.share)) }
                        OutlinedButton(onClick = { compare = !compare }) { Text(stringResource(R.string.compare)) }
                    }
                }
                if (compare) item { OutputCompare(j) }
                item {
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete_output), color = MaterialTheme.colorScheme.error) }
                }
            }
            if (status.canRetry) item { Button(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) } }
            item {
                SectionCard(stringResource(R.string.technical)) {
                    LabelValue(stringResource(R.string.job_id), j.id.toString(), mono = true)
                    LabelValue(stringResource(R.string.created), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(j.createdAt)))
                    j.completedAt?.let { LabelValue(stringResource(R.string.completed), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
                    TextButton(onClick = {
                        val text = buildReportText(j, summary, stats, error)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    }) { Text(stringResource(R.string.share_report)) }
                }
            }
        }
        if (confirmDelete) {
            ConfirmDialog(stringResource(R.string.delete_output), stringResource(R.string.delete_output_text, j.outputName), stringResource(R.string.delete),
                onConfirm = { confirmDelete = false; vm.deleteOutput(j) { ok -> if (!ok) message = context.getString(R.string.delete_failed) } },
                onDismiss = { confirmDelete = false }, destructive = true)
        }
        message?.let { m ->
            ConfirmDialog(stringResource(R.string.unavailable), m, stringResource(R.string.ok), onConfirm = { message = null }, onDismiss = { message = null })
        }
    }
}

@Composable
private fun Compare3(label: String, a: String, b: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(a.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(b.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OutputCompare(j: CompressionJobEntity) {
    val a = rememberPlayer(Uri.parse(j.sourceUri))
    val b = rememberPlayer(Uri.parse(j.outputUri!!))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) { Text(stringResource(R.string.original), style = MaterialTheme.typography.labelMedium); PlayerSurface(a) }
            Column(Modifier.weight(1f)) { Text(stringResource(R.string.compressed), style = MaterialTheme.typography.labelMedium); PlayerSurface(b) }
        }
        PlayerControls(listOf(a, b), 30.0)
    }
}

private fun open(context: android.content.Context, uri: Uri, name: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    null
} catch (_: ActivityNotFoundException) {
    context.getString(R.string.no_player_app, name)
}

private fun share(context: android.content.Context, uri: Uri) {
    val i = Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("", uri)
    context.startActivity(Intent.createChooser(i, null))
}

fun buildReportText(j: CompressionJobEntity, s: JobSummary, st: ProgressStats?, e: EngineError?): String = buildString {
    appendLine("Compression report")
    appendLine("Source: ${j.sourceName} (${Format.bytes(j.sourceSize)})")
    appendLine("Output: ${j.outputName} (${Format.bytes(j.outputBytes)})")
    appendLine("Status: ${j.status}")
    appendLine("Video: ${s.sourceCodec} ${s.sourceResolution} ${Format.fpsLabel(s.sourceFps)} -> ${s.outputCodec} ${s.outputResolution} ${Format.fpsLabel(s.outputFps)}")
    appendLine("Audio: ${s.sourceAudio} -> ${s.outputAudio}")
    appendLine("Pipeline: ${st?.pipeline?.ifBlank { null } ?: s.pipeline}  Encoder: ${st?.encoder?.ifBlank { null } ?: s.encoderDisplay}")
    appendLine("Rate control: ${s.rateControl} ${s.quality}")
    st?.let { appendLine("Time: ${Format.duration((if (it.elapsedUs > 0) it.elapsedUs else it.wallTimeUs) / 1000)}  Avg FPS: ${"%.1f".format(it.averageFps)}  Frames: ${it.frames}") }
    e?.let { appendLine("Error: ${it.categoryName} ${it.message}") }
}
