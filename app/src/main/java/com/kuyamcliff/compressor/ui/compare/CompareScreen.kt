package com.kuyamcliff.compressor.ui.compare

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.model.QualityLevel
import com.kuyamcliff.compressor.preview.ComparisonPoint
import com.kuyamcliff.compressor.ui.components.EmptyState
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.PlayerControls
import com.kuyamcliff.compressor.ui.components.PlayerSurface
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.components.rememberPlayer
import com.kuyamcliff.compressor.ui.configure.ConfigureViewModel
import com.kuyamcliff.compressor.ui.configure.LadderState
import com.kuyamcliff.compressor.ui.configure.PreviewState
import com.kuyamcliff.compressor.util.Format
import java.io.File

/**
 * Original vs compressed sample (PRD §42, §43): synchronized playback,
 * side-by-side / stacked / swipe layouts, pinch-zoom shared by both views, and
 * the quality-ladder experiment with a real size/quality graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: ConfigureViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.compare_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf(R.string.compare_sample, R.string.quality_ladder).forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                }
            }
            when (tab) {
                0 -> SampleCompare(s.current?.uri, s.preview as? PreviewState.Done, s.current?.info?.video?.video?.fps ?: 30.0)
                else -> LadderPanel(s.ladder, vm, onApplied = onBack)
            }
        }
    }
}

private enum class Layout { SIDE, STACKED, SWIPE }

@Composable
private fun SampleCompare(sourceUri: String?, done: PreviewState.Done?, fps: Double) {
    if (sourceUri == null || done == null) {
        EmptyState(stringResource(R.string.no_preview_yet), stringResource(R.string.no_preview_yet_text))
        return
    }
    val r = done.result
    val original = rememberPlayer(Uri.parse(sourceUri), r.segmentStartUs, r.segmentStartUs + r.segmentDurationUs)
    val sample = rememberPlayer(Uri.fromFile(File(r.path)))
    var layout by remember { mutableStateOf(Layout.SIDE) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var split by remember { mutableFloatStateOf(0.5f) }
    val zoom = Modifier
        .clipToBounds()
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, z, _ ->
                scale = (scale * z).coerceIn(1f, 8f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        }
    val transform = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Layout.entries.forEachIndexed { i, l ->
                    SegmentedButton(selected = layout == l, onClick = { layout = l }, shape = SegmentedButtonDefaults.itemShape(i, Layout.entries.size)) {
                        Text(stringResource(when (l) { Layout.SIDE -> R.string.layout_side; Layout.STACKED -> R.string.layout_stacked; Layout.SWIPE -> R.string.layout_swipe }))
                    }
                }
            }
        }
        item {
            when (layout) {
                Layout.SIDE -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Labeled(stringResource(R.string.original), Modifier.weight(1f)) { Box(zoom) { PlayerSurface(original, transform) } }
                    Labeled(stringResource(R.string.compressed), Modifier.weight(1f)) { Box(zoom) { PlayerSurface(sample, transform) } }
                }
                Layout.STACKED -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Labeled(stringResource(R.string.original)) { Box(zoom) { PlayerSurface(original, transform) } }
                    Labeled(stringResource(R.string.compressed)) { Box(zoom) { PlayerSurface(sample, transform) } }
                }
                Layout.SWIPE -> Column {
                    Box(zoom) {
                        PlayerSurface(original, transform)
                        // The compressed view is clipped to the right of the divider.
                        Box(Modifier.matchParentSize().clipRight(split)) { PlayerSurface(sample, transform) }
                    }
                    androidx.compose.material3.Slider(split, { split = it }, Modifier.semantics { contentDescription = "Divider" })
                    Row { Text(stringResource(R.string.original), Modifier.weight(1f)); Text(stringResource(R.string.compressed)) }
                }
            }
        }
        item { PlayerControls(listOf(original, sample), fps) }
        item {
            if (scale > 1f) TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text(stringResource(R.string.reset_zoom, "%.1f".format(scale))) }
            else Text(stringResource(R.string.pinch_to_zoom), style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionCard(stringResource(R.string.sample_stats)) {
                LabelValue(stringResource(R.string.original_sample), Format.bytes(r.sourceSegmentBytes))
                LabelValue(stringResource(R.string.compressed_sample), Format.bytes(r.sampleBytes))
                r.metrics?.let {
                    LabelValue("PSNR", "%.2f dB".format(it.psnr))
                    LabelValue("SSIM", "%.4f".format(it.ssim))
                }
                LabelValue(stringResource(R.string.encoder), "${r.encoder} (${r.pipeline})", mono = true)
                Text(stringResource(R.string.metrics_disclaimer), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Draws only the part right of [fraction] of the width (swipe compare divider). */
private fun Modifier.clipRight(fraction: Float): Modifier = drawWithContent {
    clipRect(left = size.width * fraction) { this@drawWithContent.drawContent() }
}

@Composable
private fun Labeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        content()
    }
}

@Composable
private fun LadderPanel(state: LadderState, vm: ConfigureViewModel, onApplied: () -> Unit) {
    val levels = listOf(QualityLevel.HIGH, QualityLevel.BALANCED, QualityLevel.MEDIUM, QualityLevel.SMALL, QualityLevel.VERY_SMALL)
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.ladder_explain), style = MaterialTheme.typography.bodyMedium) }
        when (state) {
            LadderState.Idle -> item { Button(onClick = { vm.runLadder(levels) }) { Text(stringResource(R.string.run_ladder, levels.size)) } }
            is LadderState.Running -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ladder_progress, state.index + 1, state.total))
                    LinearProgressIndicator(progress = { ((state.index + state.progress.coerceIn(0.0, 1.0)) / state.total).toFloat() }, Modifier.fillMaxWidth())
                    OutlinedButton(onClick = vm::cancelLadder) { Text(stringResource(R.string.cancel)) }
                }
            }
            is LadderState.Failed -> item {
                Notice(NoticeKind.ERROR, state.message, actions = { TextButton(onClick = { vm.runLadder(levels) }) { Text(stringResource(R.string.retry)) } })
            }
            is LadderState.Done -> {
                item { LadderGraph(state.points) }
                val best = recommendedPoint(state.points)
                items(state.points) { p ->
                    SectionCard(p.label, subtitle = if (p == best) stringResource(R.string.ladder_recommended) else null) {
                        LabelValue(stringResource(R.string.projected_size), Format.bytes(p.projectedBytes))
                        p.result.metrics?.let { m ->
                            LabelValue("SSIM", "%.4f".format(m.ssim))
                            LabelValue("PSNR", "%.2f dB".format(m.psnr))
                        }
                        TextButton(onClick = { vm.applyLadderPoint(p); onApplied() }) { Text(stringResource(R.string.use_this_setting)) }
                    }
                }
                item { OutlinedButton(onClick = { vm.runLadder(levels) }) { Text(stringResource(R.string.encode_again)) } }
                item { Text(stringResource(R.string.metrics_disclaimer), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * The smallest point whose SSIM stays within 0.005 of the best measured one
 * (or ≥ 0.97). Purely derived from measured values; null without metrics.
 */
fun recommendedPoint(points: List<ComparisonPoint>): ComparisonPoint? {
    val withMetrics = points.filter { it.result.metrics != null }
    if (withMetrics.isEmpty()) return null
    val bestSsim = withMetrics.maxOf { it.result.metrics!!.ssim }
    return withMetrics.filter { it.result.metrics!!.ssim >= minOf(bestSsim - 0.005, 0.97).coerceAtLeast(bestSsim - 0.02) }
        .minByOrNull { it.projectedBytes }
}

/** Size (x) vs SSIM (y) scatter/line plot of measured ladder points. */
@Composable
private fun LadderGraph(points: List<ComparisonPoint>) {
    val pts = points.filter { it.result.metrics != null }.sortedBy { it.projectedBytes }
    if (pts.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val minX = pts.first().projectedBytes.toFloat()
    val maxX = pts.last().projectedBytes.toFloat().coerceAtLeast(minX + 1)
    val minY = pts.minOf { it.result.metrics!!.ssim }.toFloat()
    val maxY = pts.maxOf { it.result.metrics!!.ssim }.toFloat().coerceAtLeast(minY + 0.001f)
    SectionCard(stringResource(R.string.size_vs_quality)) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).semantics {
                contentDescription = pts.joinToString { "${it.label}: ${Format.bytes(it.projectedBytes)}, SSIM ${"%.3f".format(it.result.metrics!!.ssim)}" }
            },
        ) {
            val pad = 12f
            fun map(p: ComparisonPoint) = Offset(
                pad + (p.projectedBytes - minX) / (maxX - minX) * (size.width - 2 * pad),
                size.height - pad - (p.result.metrics!!.ssim.toFloat() - minY) / (maxY - minY) * (size.height - 2 * pad),
            )
            drawLine(axis, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad))
            drawLine(axis, Offset(pad, pad), Offset(pad, size.height - pad))
            pts.zipWithNext().forEach { (a, b) -> drawLine(line, map(a), map(b), strokeWidth = 4f, cap = StrokeCap.Round) }
            pts.forEach { drawCircle(line, 8f, map(it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Format.bytes(minX.toLong()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.graph_axes), style = MaterialTheme.typography.labelSmall, color = Color.Unspecified)
            Text(Format.bytes(maxX.toLong()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}
