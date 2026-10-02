package com.kuyamcliff.compressor.ui.home

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.data.db.SourceMetadataCacheEntity
import com.kuyamcliff.compressor.queue.QueueSnapshot
import com.kuyamcliff.compressor.ui.components.LabeledSlider
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.NumberField
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.navigation.ConfigureRequest
import com.kuyamcliff.compressor.ui.navigation.EntryMode
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WatchedFolder(val uri: String, val label: String, val newFiles: List<Uri>)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    val recent: StateFlow<List<SourceMetadataCacheEntity>> = c.sources.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val queue: StateFlow<QueueSnapshot> = c.queue.snapshot
    private val _watched = MutableStateFlow<List<WatchedFolder>>(emptyList())
    val watched: StateFlow<List<WatchedFolder>> = _watched.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    init { refreshWatched() }

    fun configure(uris: List<Uri>, mode: EntryMode = EntryMode.NORMAL, percent: Int = 25, targetMb: Double = 0.0) {
        uris.forEach { c.sourceAccess.persistPermission(it) }
        c.session.startConfigure(ConfigureRequest(uris, entryMode = mode, makeSmallerPercent = percent, targetSizeMb = targetMb))
    }

    suspend fun videosInFolder(tree: Uri): List<Uri> = withContext(Dispatchers.IO) {
        c.sourceAccess.persistPermission(tree)
        c.sourceAccess.listVideos(tree).map { it.uri }
    }

    fun addWatched(tree: Uri) = viewModelScope.launch {
        c.sourceAccess.persistPermission(tree)
        c.settings.setWatchedFolders(c.settings.watchedFolders() + tree.toString())
        // Existing files are not "new": only files added after watching starts are offered.
        val existing = withContext(Dispatchers.IO) { c.sourceAccess.listVideos(tree).map { it.uri.toString() }.toSet() }
        c.settings.setSeenInFolder(tree.toString(), existing)
        refreshWatched()
    }

    fun removeWatched(uri: String) = viewModelScope.launch {
        c.settings.setWatchedFolders(c.settings.watchedFolders() - uri)
        refreshWatched()
    }

    /** Manual scan only: the app never polls folders in the background. */
    fun refreshWatched() = viewModelScope.launch {
        _scanning.value = true
        val list = c.settings.watchedFolders().map { f ->
            val tree = Uri.parse(f)
            val files = withContext(Dispatchers.IO) { runCatching { c.sourceAccess.listVideos(tree) }.getOrDefault(emptyList()) }
            val seen = c.settings.seenInFolder(f)
            WatchedFolder(f, tree.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: f, files.map { it.uri }.filter { it.toString() !in seen })
        }
        _watched.value = list
        _scanning.value = false
    }

    fun markSeen(folder: WatchedFolder) = viewModelScope.launch {
        c.settings.setSeenInFolder(folder.uri, c.settings.seenInFolder(folder.uri) + folder.newFiles.map { it.toString() })
        refreshWatched()
    }

    fun forgetRecent(uri: String) = viewModelScope.launch { c.sources.forget(uri) }
}

private enum class QuickAction { NONE, MAKE_SMALLER, TARGET_SIZE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onConfigure: () -> Unit, onOpenQueue: () -> Unit) {
    val recent by vm.recent.collectAsState()
    val queue by vm.queue.collectAsState()
    val watched by vm.watched.collectAsState()
    val scanning by vm.scanning.collectAsState()
    var pending by remember { mutableStateOf(QuickAction.NONE) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var folderBusy by remember { mutableStateOf(false) }
    var folderEmpty by remember { mutableStateOf(false) }
    var folderPickedUris by remember { mutableStateOf<List<Uri>?>(null) }

    fun go(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (pending == QuickAction.NONE) { vm.configure(uris); onConfigure() } else picked = uris
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { go(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { go(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) folderPickedUris = listOf(tree)
    }
    val watch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> if (tree != null) vm.addWatched(tree) }
    folderPickedUris?.firstOrNull()?.let { tree ->
        LaunchedEffect(tree) {
            folderBusy = true
            val vids = vm.videosInFolder(tree)
            folderBusy = false
            folderPickedUris = null
            if (vids.isEmpty()) folderEmpty = true else go(vids)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.home_privacy_line), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { pending = QuickAction.NONE; media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.fillMaxWidth().testTag("add_video")) {
                            Icon(Icons.Filled.Add, null); Text(stringResource(R.string.add_videos), Modifier.padding(start = 8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pending = QuickAction.NONE; docs.launch(arrayOf("video/*")) }, Modifier.weight(1f)) {
                                Icon(Icons.Outlined.VideoLibrary, null); Text(stringResource(R.string.browse_files), Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = { pending = QuickAction.NONE; folder.launch(null) }, Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Folder, null); Text(stringResource(R.string.add_folder), Modifier.padding(start = 6.dp))
                            }
                        }
                        if (folderBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.quick_actions)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pending = QuickAction.MAKE_SMALLER; media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.make_smaller))
                        }
                        FilledTonalButton(onClick = { pending = QuickAction.TARGET_SIZE; media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.target_size))
                        }
                    }
                    Text(stringResource(R.string.quick_actions_text), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!queue.idle) item {
                SectionCard(stringResource(R.string.queue), modifier = Modifier.clickable(onClick = onOpenQueue)) {
                    Text(queue.activeName ?: stringResource(R.string.n_waiting, queue.waiting))
                    if (queue.progress >= 0) LinearProgressIndicator(progress = { queue.progress.toFloat() }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        listOfNotNull(
                            if (queue.progress >= 0) Format.percent(queue.progress) else null,
                            if (queue.etaUs > 0) stringResource(R.string.eta_x, Format.duration(queue.etaUs / 1000)) else null,
                            if (queue.waiting > 0) stringResource(R.string.n_waiting, queue.waiting) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                SectionCard(stringResource(R.string.watched_folders), subtitle = stringResource(R.string.watched_folders_text), trailing = {
                    TextButton(onClick = { vm.refreshWatched() }, enabled = !scanning) { Text(stringResource(R.string.scan)) }
                }) {
                    watched.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.n_new_videos, f.newFiles.size), style = MaterialTheme.typography.bodySmall)
                            }
                            if (f.newFiles.isNotEmpty()) {
                                TextButton(onClick = { vm.markSeen(f); vm.configure(f.newFiles); onConfigure() }) { Text(stringResource(R.string.compress)) }
                                TextButton(onClick = { vm.markSeen(f) }) { Text(stringResource(R.string.ignore)) }
                            }
                            TextButton(onClick = { vm.removeWatched(f.uri) }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                    TextButton(onClick = { watch.launch(null) }) { Text(stringResource(R.string.add_watched_folder)) }
                }
            }
            if (recent.isNotEmpty()) {
                item { Text(stringResource(R.string.recent_files), style = MaterialTheme.typography.titleMedium) }
                items(recent, key = { it.uri }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.configure(listOf(Uri.parse(r.uri))); onConfigure() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(Format.bytes(r.size), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.forgetRecent(r.uri) }) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
    }

    if (folderEmpty) {
        AlertDialog(
            onDismissRequest = { folderEmpty = false },
            confirmButton = { TextButton(onClick = { folderEmpty = false }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.no_videos_in_folder)) },
            text = { Text(stringResource(R.string.no_videos_in_folder_text)) },
        )
    }

    if (picked.isNotEmpty()) {
        QuickActionDialog(
            action = pending,
            onDismiss = { picked = emptyList(); pending = QuickAction.NONE },
            onGo = { mode, percent, mb ->
                vm.configure(picked, mode, percent, mb)
                picked = emptyList(); pending = QuickAction.NONE
                onConfigure()
            },
        )
    }
}

@Composable
private fun QuickActionDialog(action: QuickAction, onDismiss: () -> Unit, onGo: (EntryMode, Int, Double) -> Unit) {
    var percent by remember { mutableStateOf(50f) }
    var mb by remember { mutableStateOf("25") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (action == QuickAction.MAKE_SMALLER) R.string.make_smaller_question else R.string.target_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action == QuickAction.MAKE_SMALLER) {
                    LabeledSlider(stringResource(R.string.make_smaller_amount), percent, { percent = it }, stringResource(R.string.percent_of_original, percent.toInt()), range = 10f..90f, steps = 15)
                } else {
                    NumberField(stringResource(R.string.target_size), mb, { mb = it }, suffix = "MB", decimal = true)
                    Notice(NoticeKind.INFO, stringResource(R.string.target_size_quick_text))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (action == QuickAction.MAKE_SMALLER) onGo(EntryMode.MAKE_SMALLER, percent.toInt(), 0.0)
                    else onGo(EntryMode.TARGET_SIZE, 0, mb.toDoubleOrNull() ?: 0.0)
                },
                enabled = action == QuickAction.MAKE_SMALLER || (mb.toDoubleOrNull() ?: 0.0) > 0,
            ) { Text(stringResource(R.string.continue_)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
