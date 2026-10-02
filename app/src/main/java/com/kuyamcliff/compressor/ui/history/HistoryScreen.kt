package com.kuyamcliff.compressor.ui.history

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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.data.db.CompressionHistoryEntity
import com.kuyamcliff.compressor.data.repo.AppJson
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.ui.components.EmptyState
import com.kuyamcliff.compressor.ui.components.Pill
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    val items: StateFlow<List<CompressionHistoryEntity>> = c.history.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun delete(id: Long) = viewModelScope.launch { c.history.delete(id) }

    /** "Use these settings again" — loads the entry's configuration for a new file. */
    fun configOf(h: CompressionHistoryEntity): CompressionConfig? = runCatching { AppJson.decodeConfig(h.configJson) }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpenJob: (Long) -> Unit, onReuse: (CompressionConfig, List<android.net.Uri>) -> Unit) {
    var reuse by remember { mutableStateOf<CompressionConfig?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        reuse?.let { cfg -> if (uris.isNotEmpty()) onReuse(cfg, uris) }
        reuse = null
    }
    val all by vm.items.collectAsState()
    var query by remember { mutableStateOf("") }
    val list = remember(all, query) { if (query.isBlank()) all else all.filter { it.sourceName.contains(query, true) || it.outputName.contains(query, true) || it.outputCodec.contains(query, true) } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.history)) }) }) { pad ->
        if (all.isEmpty()) {
            EmptyState(stringResource(R.string.history_empty), stringResource(R.string.history_empty_text), Modifier.padding(pad))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val inBytes = all.sumOf { it.sourceSize }
                val outBytes = all.sumOf { it.outputSize }
                SectionCard(stringResource(R.string.totals)) {
                    Text(stringResource(R.string.history_totals, all.size, Format.bytes(inBytes), Format.bytes(outBytes), Format.bytes((inBytes - outBytes).coerceAtLeast(0))))
                }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search)) }, singleLine = true)
            }
            items(list, key = { it.id }) { h ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { onOpenJob(h.jobId) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.sourceName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (h.outputDeleted) Pill(stringResource(R.string.output_deleted))
                        }
                        Text(
                            "${Format.bytes(h.sourceSize)} → ${Format.bytes(h.outputSize)}" +
                                if (h.sourceSize > 0) " (−${Format.percent((h.sourceSize - h.outputSize).toDouble() / h.sourceSize)})" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("${h.sourceCodec} ${h.sourceResolution} → ${h.outputCodec} ${h.outputResolution} · ${h.pipeline}", style = MaterialTheme.typography.bodySmall)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(h.completedAt)) + " · " + Format.duration(h.encodeTimeMs), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { vm.configOf(h)?.let { reuse = it; picker.launch(arrayOf("video/*")) } }) { Text(stringResource(R.string.reuse_settings)) }
                            TextButton(onClick = { vm.delete(h.id) }) { Text(stringResource(R.string.remove_from_history)) }
                        }
                    }
                }
            }
        }
    }
}
