package com.kuyamcliff.compressor.ui.configure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.model.SourceFile
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.Pill
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.util.Format

/** Source analysis (PRD §10): file, video, audio, subtitles, chapters, metadata. */
@Composable
fun SourceAnalysisCard(file: SourceFile) {
    var expanded by remember { mutableStateOf(false) }
    val info = file.info
    val v = info.video
    val vi = v?.video
    SectionCard(
        stringResource(R.string.source_analysis),
        subtitle = listOfNotNull(
            Format.bytes(file.sizeBytes),
            Format.duration(info.durationUs / 1000),
            vi?.let { "${it.displayWidth}×${it.displayHeight}" },
            vi?.let { Format.fpsLabel(it.fps) + " FPS" },
            v?.codec?.uppercase(),
        ).joinToString(" · "),
        trailing = {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.less else R.string.details)) }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vi?.let {
                if (it.isHdr) Pill(it.hdr.uppercase())
                if (it.bitDepth > 8) Pill("${it.bitDepth}-bit")
                if (it.rotation != 0) Pill("${it.rotation}°")
                if (it.isInterlaced) Pill(stringResource(R.string.interlaced))
            }
            v?.timing?.vfr?.let { Pill(if (it) "VFR" else "CFR") }
            if (info.audioStreams.size > 1) Pill(stringResource(R.string.n_audio_tracks, info.audioStreams.size))
            if (info.subtitleStreams.isNotEmpty()) Pill(stringResource(R.string.n_subtitles, info.subtitleStreams.size))
        }
        if (!expanded) return@SectionCard
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.file), style = MaterialTheme.typography.labelLarge)
            LabelValue(stringResource(R.string.name), file.displayName)
            LabelValue(stringResource(R.string.extension), file.extension)
            LabelValue(stringResource(R.string.size), Format.bytes(file.sizeBytes))
            if (file.location.isNotBlank()) LabelValue(stringResource(R.string.location), file.location)
            LabelValue(stringResource(R.string.container), info.format.longName.ifBlank { info.format.name })
            LabelValue(stringResource(R.string.overall_bitrate), Format.bitrate(info.format.bitrate))
            if (v != null && vi != null) {
                Text(stringResource(R.string.video), style = MaterialTheme.typography.labelLarge)
                LabelValue(stringResource(R.string.codec), "${v.codec.uppercase()} ${v.profile}".trim())
                if (v.encoder.isNotBlank() || info.encoder.isNotBlank()) LabelValue(stringResource(R.string.encoder), v.encoder.ifBlank { info.encoder })
                LabelValue(stringResource(R.string.resolution), "${vi.width}×${vi.height}" + if (vi.displayWidth != vi.width || vi.displayHeight != vi.height) " → ${vi.displayWidth}×${vi.displayHeight}" else "")
                LabelValue(stringResource(R.string.aspect_ratio), "%.3f:1".format(vi.aspectRatio) + if (vi.sarNum != vi.sarDen) " (SAR ${vi.sarNum}:${vi.sarDen})" else "")
                LabelValue("FPS", Format.fpsLabel(vi.fps) + (v.timing?.vfr?.let { if (it) " (VFR)" else " (CFR)" } ?: ""))
                LabelValue(stringResource(R.string.bitrate), Format.bitrate(v.bitrate) + if (v.bitrateEstimated) " (${stringResource(R.string.estimated)})" else "")
                LabelValue(stringResource(R.string.pixel_format), "${vi.pixFmt} · ${vi.bitDepth}-bit ${vi.chroma}", mono = true)
                LabelValue(stringResource(R.string.color_space), vi.colorSpace.ifBlank { "—" }, mono = true)
                LabelValue(stringResource(R.string.color_primaries), vi.colorPrimaries.ifBlank { "—" }, mono = true)
                LabelValue(stringResource(R.string.transfer), vi.colorTransfer.ifBlank { "—" }, mono = true)
                LabelValue(stringResource(R.string.range), vi.colorRange.ifBlank { "—" }, mono = true)
                LabelValue("HDR", vi.hdr.uppercase())
                LabelValue(stringResource(R.string.rotation), "${vi.rotation}°")
            }
            info.audioStreams.forEachIndexed { i, a ->
                Text(stringResource(R.string.audio_track_n, i + 1), style = MaterialTheme.typography.labelLarge)
                LabelValue(stringResource(R.string.language), a.displayLanguage)
                LabelValue(stringResource(R.string.codec), a.codec.uppercase())
                LabelValue(stringResource(R.string.bitrate), Format.bitrate(a.bitrate))
                LabelValue(stringResource(R.string.channels), "${a.audio?.channels ?: 0} (${a.audio?.channelLayout.orEmpty()})")
                LabelValue(stringResource(R.string.sample_rate), "${a.audio?.sampleRate ?: 0} Hz")
                if (a.title.isNotBlank()) LabelValue(stringResource(R.string.title), a.title)
            }
            info.subtitleStreams.forEachIndexed { i, s ->
                Text(stringResource(R.string.subtitle_n, i + 1), style = MaterialTheme.typography.labelLarge)
                LabelValue(stringResource(R.string.language), s.displayLanguage)
                LabelValue(stringResource(R.string.type), "${s.codec} · ${if (s.subtitle?.bitmap == true) stringResource(R.string.bitmap) else stringResource(R.string.text)}")
                LabelValue(stringResource(R.string.flags), listOfNotNull(if (s.default) stringResource(R.string.default_flag) else null, if (s.forced) stringResource(R.string.forced) else null).joinToString().ifBlank { "—" })
            }
            Text(stringResource(R.string.chapters), style = MaterialTheme.typography.labelLarge)
            LabelValue(stringResource(R.string.count), info.format.chapters.size.toString())
            if (info.format.tags.isNotEmpty()) {
                Text(stringResource(R.string.metadata), style = MaterialTheme.typography.labelLarge)
                info.format.tags.entries.take(20).forEach { (k, value) -> LabelValue(k, value) }
            }
        }
    }
}
