package com.kuyamcliff.compressor.ui.queue

import android.app.Application
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.data.db.CompressionJobEntity
import com.kuyamcliff.compressor.model.JobPriority
import com.kuyamcliff.compressor.model.JobStatus
import com.kuyamcliff.compressor.queue.LiveJob
import com.kuyamcliff.compressor.ui.Labels
import com.kuyamcliff.compressor.ui.components.ConfirmDialog
import com.kuyamcliff.compressor.ui.components.EmptyState
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.Pill
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    val jobs: StateFlow<List<CompressionJobEntity>> = c.jobs.queue.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val live: StateFlow<Map<Long, LiveJob>> = c.queue.live
    val policy: StateFlow<String?> = c.queue.policyMessage

    private fun act(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    fun pause(id: Long) = act { c.queue.pause(id) }
    fun resume(id: Long) = act { c.queue.resume(id) }
    fun cancel(id: Long) = act { c.queue.cancel(id) }
    fun retry(id: Long) = act { c.queue.retry(id) }
    fun remove(id: Long) = act { c.queue.remove(id) }
    fun move(id: Long, dir: Int) = act { c.jobs.move(id, dir) }
    fun priority(id: Long, p: JobPriority) = act { c.jobs.setPriority(id, p); c.queue.kick() }
    fun pauseAll() = act { c.queue.pauseAll() }
    fun resumeAll() = act { c.queue.resumeAll() }
    fun cancelAll() = act { c.queue.cancelAll() }
    fun clearFinished() = act { c.jobs.clearCompleted() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: QueueViewModel, onOpenJob: (Long) -> Unit) {
    val jobs by vm.jobs.collectAsState()
    val live by vm.live.collectAsState()
    val policy by vm.policy.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.queue)) }, actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.pause_all)) }, onClick = { menu = false; vm.pauseAll() })
                DropdownMenuItem(text = { Text(stringResource(R.string.resume_all)) }, onClick = { menu = false; vm.resumeAll() })
                DropdownMenuItem(text = { Text(stringResource(R.string.cancel_all)) }, onClick = { menu = false; confirmCancelAll = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.clear_finished)) }, onClick = { menu = false; vm.clearFinished() })
            }
        })
    }) { pad ->
        if (jobs.isEmpty()) {
            EmptyState(stringResource(R.string.queue_empty), stringResource(R.string.queue_empty_text), Modifier.padding(pad))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            policy?.let { item { Notice(NoticeKind.WARNING, it) } }
            items(jobs, key = { it.id }) { j -> JobCard(j, live[j.id], vm, onOpenJob) }
        }
    }
    if (confirmCancelAll) {
        ConfirmDialog(stringResource(R.string.cancel_all), stringResource(R.string.cancel_all_text), stringResource(R.string.cancel_all),
            onConfirm = { confirmCancelAll = false; vm.cancelAll() }, onDismiss = { confirmCancelAll = false }, destructive = true)
    }
}

@Composable
private fun JobCard(j: CompressionJobEntity, live: LiveJob?, vm: QueueViewModel, onOpen: (Long) -> Unit) {
    val status = runCatching { JobStatus.valueOf(j.status) }.getOrDefault(JobStatus.FAILED)
    val stats = live?.stats
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(j.id) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(j.sourceName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("→ ${j.outputName}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Pill(Labels.status(status))
            }
            if (status.isRunning) {
                val p = stats?.progress ?: j.progress.toDouble()
                if (p >= 0) LinearProgressIndicator(progress = { p.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().semantics { contentDescription = Format.percent(p) })
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    listOfNotNull(
                        if (p >= 0) Format.percent(p) else stringResource(R.string.calculating),
                        stats?.takeIf { it.currentFps > 0 }?.let { "%.1f fps".format(it.currentFps) },
                        stats?.takeIf { it.speed > 0 }?.let { Format.speed(it.speed) },
                        stats?.takeIf { it.etaUs > 0 }?.let { stringResource(R.string.eta_x, Format.duration(it.etaUs / 1000)) },
                        stats?.takeIf { it.passes > 1 }?.let { stringResource(R.string.pass_x_of_y, it.pass, it.passes) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                stats?.takeIf { it.outputBytes > 0 }?.let {
                    Text(stringResource(R.string.written_projected, Format.bytes(it.outputBytes), if (it.projectedBytes > 0) Format.bytes(it.projectedBytes) else "—"), style = MaterialTheme.typography.bodySmall)
                }
                (live?.encoder ?: j.pipeline.ifBlank { null })?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                if (live?.validating == true) Text(stringResource(R.string.validating_output), style = MaterialTheme.typography.bodySmall)
            }
            j.statusReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (status == JobStatus.FAILED || status == JobStatus.STORAGE_ERROR || status == JobStatus.UNSUPPORTED) {
                Text(j.errorMessage ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (status == JobStatus.COMPLETE && j.outputBytes > 0) {
                Text(stringResource(R.string.saved_x, Format.bytes(j.sourceSize), Format.bytes(j.outputBytes),
                    if (j.sourceSize > 0) Format.percent((j.sourceSize - j.outputBytes).toDouble() / j.sourceSize) else "—"), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    status == JobStatus.PAUSED -> IconButton(onClick = { vm.resume(j.id) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_resume)) }
                    status.isActive || status == JobStatus.WAITING -> IconButton(onClick = { vm.pause(j.id) }) { Icon(Icons.Filled.Pause, stringResource(R.string.action_pause)) }
                }
                if (status.canRetry) IconButton(onClick = { vm.retry(j.id) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.retry)) }
                if (!status.isTerminal && status != JobStatus.INTERRUPTED) IconButton(onClick = { vm.cancel(j.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                if (status == JobStatus.WAITING) {
                    IconButton(onClick = { vm.move(j.id, -1) }) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.move_up)) }
                    IconButton(onClick = { vm.move(j.id, 1) }) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.move_down)) }
                }
                Row(Modifier.weight(1f)) {}
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    if (!status.isTerminal) JobPriority.entries.forEach { p ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.priority_x, stringResource(when (p) { JobPriority.LOW -> R.string.priority_low; JobPriority.NORMAL -> R.string.priority_normal; JobPriority.HIGH -> R.string.priority_high }))) },
                            onClick = { menu = false; vm.priority(j.id, p) })
                    }
                    if (status.isTerminal || status == JobStatus.INTERRUPTED) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; vm.remove(j.id) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.details)) }, onClick = { menu = false; onOpen(j.id) })
                }
            }
        }
    }
}
