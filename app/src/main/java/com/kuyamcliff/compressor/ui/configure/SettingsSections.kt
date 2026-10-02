package com.kuyamcliff.compressor.ui.configure

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.domain.Codecs
import com.kuyamcliff.compressor.domain.Compatibility
import com.kuyamcliff.compressor.domain.FileNaming
import com.kuyamcliff.compressor.domain.Geometry
import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.AudioQuality
import com.kuyamcliff.compressor.model.AudioTrackSettings
import com.kuyamcliff.compressor.model.BitDepth
import com.kuyamcliff.compressor.model.ChannelChoice
import com.kuyamcliff.compressor.model.ChapterEdit
import com.kuyamcliff.compressor.model.ChapterMode
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.ConflictPolicy
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.CropMode
import com.kuyamcliff.compressor.model.Deinterlace
import com.kuyamcliff.compressor.model.Denoiser
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.ExternalSubtitle
import com.kuyamcliff.compressor.model.FilterStrength
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.HdrMode
import com.kuyamcliff.compressor.model.MetadataMode
import com.kuyamcliff.compressor.model.QualityLevel
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.ResolutionChoice
import com.kuyamcliff.compressor.model.Scaler
import com.kuyamcliff.compressor.model.Sharpener
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.model.SubtitleTrackSettings
import com.kuyamcliff.compressor.model.VideoCodec
import com.kuyamcliff.compressor.model.VideoMode
import com.kuyamcliff.compressor.ui.Labels
import com.kuyamcliff.compressor.ui.components.ChoiceChips
import com.kuyamcliff.compressor.ui.components.DropdownSetting
import com.kuyamcliff.compressor.ui.components.LabelValue
import com.kuyamcliff.compressor.ui.components.LabeledSlider
import com.kuyamcliff.compressor.ui.components.Notice
import com.kuyamcliff.compressor.ui.components.NoticeKind
import com.kuyamcliff.compressor.ui.components.NumberField
import com.kuyamcliff.compressor.ui.components.SectionCard
import com.kuyamcliff.compressor.ui.components.SettingHeader
import com.kuyamcliff.compressor.ui.components.SwitchRow
import kotlin.math.roundToInt

private data class SectionDef(
    val id: String,
    val tier: SettingsTier,
    val keywords: List<String>,
    val content: @Composable (ConfigureUiState, ConfigureViewModel, () -> Unit) -> Unit,
)

private val sections = listOf(
    SectionDef("quality", SettingsTier.BASIC, listOf("quality", "crf", "rf", "qp", "bitrate", "target", "size", "lossless", "2-pass", "two pass", "rate control")) { s, vm, _ -> QualitySection(s, vm) },
    SectionDef("codec", SettingsTier.BASIC, listOf("codec", "h.264", "hevc", "h.265", "av1", "vp9", "hardware", "software", "engine", "encoder")) { s, vm, _ -> CodecSection(s, vm) },
    SectionDef("resolution", SettingsTier.BASIC, listOf("resolution", "1080p", "720p", "4k", "scale", "size", "upscale", "aspect")) { s, vm, _ -> ResolutionSection(s, vm) },
    SectionDef("fps", SettingsTier.BASIC, listOf("fps", "frame rate", "framerate", "vfr", "cfr", "60", "30")) { s, vm, _ -> FpsSection(s, vm) },
    SectionDef("format", SettingsTier.BASIC, listOf("format", "container", "mp4", "mkv", "webm", "mov", "fast start", "file name", "output", "folder")) { s, vm, pick -> FormatSection(s, vm, pick) },
    SectionDef("audio", SettingsTier.BASIC, listOf("audio", "aac", "opus", "mp3", "flac", "channels", "stereo", "sample rate", "passthrough", "tracks", "language")) { s, vm, _ -> AudioSection(s, vm) },
    SectionDef("crop", SettingsTier.ADVANCED, listOf("crop", "scaling", "scaler", "lanczos", "bicubic", "rotate", "flip")) { s, vm, _ -> CropSection(s, vm) },
    SectionDef("filters", SettingsTier.ADVANCED, listOf("filter", "deinterlace", "detelecine", "deblock", "denoise", "sharpen", "deband", "grayscale")) { s, vm, _ -> FilterSection(s, vm) },
    SectionDef("hdr", SettingsTier.ADVANCED, listOf("hdr", "hlg", "pq", "10-bit", "bit depth", "tone", "color", "colour", "primaries", "transfer", "matrix", "range")) { s, vm, _ -> HdrSection(s, vm) },
    SectionDef("subtitles", SettingsTier.ADVANCED, listOf("subtitle", "srt", "ass", "burn", "forced", "caption")) { s, vm, _ -> SubtitleSection(s, vm) },
    SectionDef("chapters", SettingsTier.ADVANCED, listOf("chapter")) { s, vm, _ -> ChapterSection(s, vm) },
    SectionDef("metadata", SettingsTier.ADVANCED, listOf("metadata", "title", "artist", "tags", "strip")) { s, vm, _ -> MetadataSection(s, vm) },
    SectionDef("queue", SettingsTier.ADVANCED, listOf("priority", "queue", "replace original", "delete original")) { s, vm, _ -> QueueOptionsSection(s, vm) },
    SectionDef("expert", SettingsTier.EXPERT, listOf("profile", "level", "preset", "tune", "gop", "keyframe", "b-frame", "bframes", "reference", "threads", "options", "pixel format", "vbv", "maxrate")) { s, vm, _ -> ExpertSection(s, vm) },
    SectionDef("hardware", SettingsTier.EXPERT, listOf("hardware", "component", "mediacodec", "surface")) { s, vm, _ -> HardwareSection(s) },
)

/** Shows the sections for the current tier, or every section matching the search (PRD §91, §192). */
fun LazyListScope.settingsSections(s: ConfigureUiState, vm: ConfigureViewModel, onPickFolder: () -> Unit) {
    val q = s.search.trim().lowercase()
    val visible = if (q.isNotEmpty()) sections.filter { d -> d.id.contains(q) || d.keywords.any { it.contains(q) || q.contains(it) } }
    else sections.filter { it.tier.ordinal <= s.tier.ordinal }
    if (q.isNotEmpty() && visible.isEmpty()) {
        item { Text(stringResource(R.string.no_settings_match)) }
    }
    visible.forEach { d -> item(key = d.id) { d.content(s, vm, onPickFolder) } }
}

private fun CompressionConfig.v(f: (com.kuyamcliff.compressor.model.VideoSettings) -> com.kuyamcliff.compressor.model.VideoSettings) = copy(video = f(video))

// ------------------------------------------------------------------ quality

@Composable
private fun QualitySection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    val sw = Codecs.info(v.codec)
    SectionCard(stringResource(R.string.sec_quality), trailing = { TextButton(onClick = { vm.resetSection("quality") }) { Text(stringResource(R.string.reset)) } }) {
        if (v.mode == VideoMode.COPY) {
            Text(stringResource(R.string.copy_mode_quality))
            return@SectionCard
        }
        SettingHeader(stringResource(R.string.size_strategy), stringResource(R.string.help_size_strategy))
        val modes = listOf(RateControlMode.CONSTANT_QUALITY, RateControlMode.TARGET_SIZE, RateControlMode.AVERAGE_BITRATE, RateControlMode.CONSTANT_BITRATE, RateControlMode.LOSSLESS)
        ChoiceChips(modes, v.rateControl, { Labels.rateControl(it) }, { m -> vm.edit { it.v { x -> x.copy(rateControl = m) } } },
            disabledReason = { m -> if (m == RateControlMode.LOSSLESS && !sw.scale.lossless) stringResource(R.string.lossless_unavailable_codec) else null })
        when (v.rateControl) {
            RateControlMode.CONSTANT_QUALITY -> {
                Text(stringResource(R.string.cq_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(QualityLevel.selectable, if (v.qualityLevel == QualityLevel.CUSTOM) null else v.qualityLevel, { Labels.quality(it) },
                    { q -> vm.edit { it.v { x -> x.copy(qualityLevel = q, qualitySlider = q.slider, nativeQuality = null) } } })
                LabeledSlider(
                    title = stringResource(R.string.quality),
                    value = v.effectiveSlider,
                    onChange = { f -> vm.editLive { it.v { x -> x.copy(qualityLevel = QualityLevel.CUSTOM, qualitySlider = f, nativeQuality = null) } } },
                    onChangeFinished = vm::commit,
                    valueLabel = if (s.resolved?.summary?.pipeline == com.kuyamcliff.compressor.domain.PipelineKind.SOFTWARE) "${sw.scale.label} ${sw.scale.fromSlider(v.effectiveSlider).roundToInt()}" else "${(v.effectiveSlider * 100).roundToInt()}%",
                    startLabel = stringResource(R.string.smallest_size), endLabel = stringResource(R.string.maximum_quality),
                    modifier = Modifier.testTag("quality_slider"),
                )
                val level = if (v.qualityLevel == QualityLevel.CUSTOM) QualityLevel.nearest(v.qualitySlider) else v.qualityLevel
                Text(Labels.qualityHelp(level), style = MaterialTheme.typography.bodySmall)
            }
            RateControlMode.TARGET_SIZE -> {
                var text by remember(v.targetSizeMb) { mutableStateOf(if (v.targetSizeMb > 0) trimNum(v.targetSizeMb) else "") }
                NumberField(stringResource(R.string.target_size), text, { t ->
                    text = t
                    t.toDoubleOrNull()?.let { mb -> vm.edit { it.v { x -> x.copy(targetSizeMb = mb.coerceIn(0.1, 1_000_000.0)) } } }
                }, suffix = "MB", decimal = true, modifier = Modifier.testTag("target_size_field"))
                s.resolved?.summary?.budget?.let { b ->
                    LabelValue(stringResource(R.string.video_budget), "${b.videoKbps} kbps")
                    LabelValue(stringResource(R.string.audio_share), "%.0f%%".format(b.audioShare * 100))
                    s.resolved.summary.feasibility?.let { f -> LabelValue(stringResource(R.string.expected_quality_loss), Labels.risk(f.risk)) }
                }
                SwitchRow(stringResource(R.string.smart_target), v.smartTarget, { b -> vm.edit { it.v { x -> x.copy(smartTarget = b) } } },
                    subtitle = stringResource(R.string.smart_target_desc))
                TwoPassRow(s, vm)
            }
            RateControlMode.AVERAGE_BITRATE, RateControlMode.CONSTANT_BITRATE -> {
                var text by remember(v.bitrateKbps) { mutableStateOf(v.bitrateKbps.toString()) }
                NumberField(stringResource(R.string.video_bitrate), text, { t -> text = t; t.toIntOrNull()?.let { k -> vm.edit { it.v { x -> x.copy(bitrateKbps = k) } } } }, suffix = "kbps")
                if (v.rateControl == RateControlMode.AVERAGE_BITRATE) TwoPassRow(s, vm)
            }
            RateControlMode.LOSSLESS -> Text(stringResource(R.string.lossless_explain), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.disclaimer_quality), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TwoPassRow(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    val sw = Codecs.info(v.codec)
    val reason = when {
        !sw.supportsTwoPass -> stringResource(R.string.two_pass_unavailable_codec, Labels.codecShort(v.codec))
        s.resolved?.summary?.pipeline == com.kuyamcliff.compressor.domain.PipelineKind.HARDWARE || s.resolved?.summary?.pipeline == com.kuyamcliff.compressor.domain.PipelineKind.HYBRID || v.engine == EngineChoice.HARDWARE ->
            stringResource(R.string.two_pass_unavailable_hw)
        else -> null
    }
    SwitchRow(stringResource(R.string.two_pass), v.twoPass && reason == null, { b -> vm.edit { it.v { x -> x.copy(twoPass = b) } } },
        enabled = reason == null, subtitle = reason ?: stringResource(R.string.two_pass_desc), help = stringResource(R.string.help_two_pass))
}

private fun trimNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)

// -------------------------------------------------------------------- codec

@Composable
private fun CodecSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    SectionCard(stringResource(R.string.sec_codec)) {
        val source = s.current?.info?.video
        val canCopy = source != null && Compatibility.canCopyVideo(s.config.container, source.codec)
        SwitchRow(stringResource(R.string.fast_remux), v.mode == VideoMode.COPY, { b -> vm.edit { it.v { x -> x.copy(mode = if (b) VideoMode.COPY else VideoMode.TRANSCODE) } } },
            subtitle = if (canCopy) stringResource(R.string.fast_remux_desc) else stringResource(R.string.fast_remux_unavailable, s.current?.info?.video?.codec?.uppercase() ?: "", Labels.container(s.config.container)),
            enabled = canCopy || v.mode == VideoMode.COPY)
        if (v.mode == VideoMode.COPY) return@SectionCard
        val main = VideoCodec.entries.filter { !it.isOther }
        val others = VideoCodec.entries.filter { it.isOther }
        ChoiceChips(if (s.tier == SettingsTier.BASIC && !v.codec.isOther) main else main + others, v.codec, { Labels.codecShort(it) },
            { c -> vm.edit { it.v { x -> x.copy(codec = c, encoderPreset = null, tune = null, profile = null, nativeQuality = null, advancedOptions = emptyMap()) } } },
            disabledReason = { c -> if (!Compatibility.supports(s.config.container, c)) stringResource(R.string.codec_not_in_container, Labels.codecShort(c), Labels.container(s.config.container)) else null })
        Text(Labels.codecHelp(v.codec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingHeader(stringResource(R.string.encoding_engine), stringResource(R.string.help_engine))
        ChoiceChips(EngineChoice.entries, v.engine, { Labels.engine(it) }, { e -> vm.edit { it.v { x -> x.copy(engine = e) } } })
        if (v.engine != EngineChoice.SOFTWARE && s.hardwareProblems.isNotEmpty()) {
            Notice(NoticeKind.INFO, s.hardwareProblems.first(), title = stringResource(R.string.hw_unavailable_title, Labels.codecShort(v.codec)))
        }
        Text(stringResource(R.string.hw_vs_sw), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --------------------------------------------------------------- resolution

@Composable
private fun ResolutionSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    val src = s.current?.info?.video?.video ?: return
    if (v.mode == VideoMode.COPY) return
    SectionCard(stringResource(R.string.sec_resolution), subtitle = stringResource(R.string.source_is, "${src.displayWidth}×${src.displayHeight}")) {
        val shortSide = minOf(src.displayWidth, src.displayHeight)
        val options = ResolutionChoice.entries.filter { it == ResolutionChoice.SOURCE || it == ResolutionChoice.CUSTOM || it.shortSide <= shortSide || v.allowUpscale }
        ChoiceChips(options, v.resolution, { Labels.resolution(it) }, { r -> vm.edit { it.v { x -> x.copy(resolution = r) } } })
        if (v.resolution == ResolutionChoice.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var w by remember { mutableStateOf(if (v.customWidth > 0) v.customWidth.toString() else "") }
                var h by remember { mutableStateOf(if (v.customHeight > 0) v.customHeight.toString() else "") }
                NumberField(stringResource(R.string.width), w, { t -> w = t; vm.edit { it.v { x -> x.copy(customWidth = t.toIntOrNull() ?: 0) } } }, Modifier.weight(1f))
                NumberField(stringResource(R.string.height), h, { t -> h = t; vm.edit { it.v { x -> x.copy(customHeight = t.toIntOrNull() ?: 0) } } }, Modifier.weight(1f))
            }
            SwitchRow(stringResource(R.string.keep_aspect), v.keepAspect, { b -> vm.edit { it.v { x -> x.copy(keepAspect = b) } } })
        }
        SwitchRow(stringResource(R.string.allow_upscale), v.allowUpscale, { b -> vm.edit { it.v { x -> x.copy(allowUpscale = b) } } }, subtitle = stringResource(R.string.allow_upscale_desc))
        s.resolved?.summary?.outputSize?.let { LabelValue(stringResource(R.string.output), it.toString()) }
    }
}

// ---------------------------------------------------------------------- fps

@Composable
private fun FpsSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    val src = s.current?.info?.video?.video ?: return
    if (v.mode == VideoMode.COPY) return
    SectionCard(stringResource(R.string.sec_fps), subtitle = stringResource(R.string.source_is, com.kuyamcliff.compressor.util.Format.fpsLabel(src.fps))) {
        ChoiceChips(FpsChoice.entries, v.fps, { Labels.fps(it) }, { f ->
            vm.edit { it.v { x -> x.copy(fps = f, fpsMode = if (f == FpsChoice.SOURCE) FpsMode.VFR else if (x.fpsMode == FpsMode.VFR) FpsMode.PEAK else x.fpsMode) } }
        })
        if (v.fps == FpsChoice.CUSTOM) {
            var t by remember { mutableStateOf(trimNum(v.customFps)) }
            NumberField(stringResource(R.string.custom_fps), t, { x -> t = x; x.toDoubleOrNull()?.let { f -> vm.edit { it.v { y -> y.copy(customFps = f.coerceIn(1.0, 240.0)) } } } }, decimal = true)
        }
        SettingHeader(stringResource(R.string.timing), stringResource(R.string.help_vfr))
        val modes = if (v.fps == FpsChoice.SOURCE) listOf(FpsMode.VFR, FpsMode.CFR) else listOf(FpsMode.PEAK, FpsMode.CFR)
        ChoiceChips(modes, v.fpsMode, { Labels.fpsMode(it) }, { m -> vm.edit { it.v { x -> x.copy(fpsMode = m) } } })
    }
}

// ------------------------------------------------------------------- format

@Composable
private fun FormatSection(s: ConfigureUiState, vm: ConfigureViewModel, onPickFolder: () -> Unit) {
    val c = s.config
    SectionCard(stringResource(R.string.sec_format)) {
        val containers = if (s.tier == SettingsTier.BASIC) listOf(Container.MP4, Container.MKV, Container.WEBM) else Container.entries
        ChoiceChips(containers, c.container, { Labels.container(it) }, { k -> vm.edit { it.copy(container = k) } },
            disabledReason = { k -> if (c.video.mode == VideoMode.TRANSCODE && !Compatibility.supports(k, c.video.codec)) stringResource(R.string.codec_not_in_container, Labels.codecShort(c.video.codec), Labels.container(k)) else null })
        if (c.container == Container.MP4 || c.container == Container.MOV) {
            SwitchRow(stringResource(R.string.fast_start), c.output.fastStart, { b -> vm.edit { it.copy(output = it.output.copy(fastStart = b)) } }, subtitle = stringResource(R.string.fast_start_desc))
            if (s.tier != SettingsTier.BASIC) SwitchRow(stringResource(R.string.fragmented), c.output.fragmented, { b -> vm.edit { it.copy(output = it.output.copy(fragmented = b)) } }, subtitle = stringResource(R.string.fragmented_desc))
        }
        if (s.tier != SettingsTier.BASIC) {
            var tpl by remember { mutableStateOf(c.output.fileNameTemplate) }
            OutlinedTextField(tpl, { t -> tpl = t.take(80); vm.edit { it.copy(output = it.output.copy(fileNameTemplate = tpl)) } },
                label = { Text(stringResource(R.string.file_name_template)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(FileNaming.tokens.joinToString(" ")) })
            s.current?.let { f ->
                val preview = FileNaming.render(c.output.fileNameTemplate, FileNaming.Values(f.displayName, Labels.codecShort(c.video.codec), s.resolved?.summary?.outputSize?.let { "${it.shortSide}p" } ?: ""), c.container.extension)
                LabelValue(stringResource(R.string.output_name), preview)
            }
            LabelValue(stringResource(R.string.output_folder), c.output.folderUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') } ?: "Movies/Compressed")
            Row {
                TextButton(onClick = onPickFolder) { Text(stringResource(R.string.choose_folder)) }
                if (c.output.folderUri != null) TextButton(onClick = { vm.edit { it.copy(output = it.output.copy(folderUri = null)) } }) { Text(stringResource(R.string.use_default)) }
            }
            ChoiceChips(ConflictPolicy.entries, c.output.conflict, {
                stringResource(when (it) { ConflictPolicy.ASK -> R.string.conflict_ask; ConflictPolicy.RENAME -> R.string.conflict_rename; ConflictPolicy.REPLACE -> R.string.conflict_replace })
            }, { p -> vm.edit { it.copy(output = it.output.copy(conflict = p)) } })
        }
    }
}

// -------------------------------------------------------------------- audio

@Composable
private fun AudioSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val a = s.config.audio
    val info = s.current?.info ?: return
    SectionCard(stringResource(R.string.sec_audio), trailing = { TextButton(onClick = { vm.resetSection("audio") }) { Text(stringResource(R.string.reset)) } }) {
        if (info.audioStreams.isEmpty()) { Text(stringResource(R.string.no_audio_in_source)); return@SectionCard }
        SwitchRow(stringResource(R.string.remove_audio), a.removeAudio, { b -> vm.edit { it.copy(audio = it.audio.copy(removeAudio = b)) } })
        if (a.removeAudio) return@SectionCard
        ChoiceChips(AudioCodec.entries.filter { s.tier != SettingsTier.BASIC || it in listOf(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.MP3) || it == a.codec }, a.codec, { Labels.audioCodec(it) },
            { codec -> vm.edit { it.copy(audio = it.audio.copy(codec = codec)) } },
            disabledReason = { codec -> if (!Compatibility.supports(s.config.container, codec)) stringResource(R.string.audio_not_in_container, Labels.audioCodec(codec), Labels.container(s.config.container)) else null })
        if (!a.codec.lossless) {
            SettingHeader(stringResource(R.string.audio_quality), stringResource(R.string.help_audio_quality))
            ChoiceChips(AudioQuality.entries, a.quality, { Labels.audioQuality(it) }, { q -> vm.edit { it.copy(audio = it.audio.copy(quality = q)) } })
            if (a.quality == AudioQuality.CUSTOM) {
                ChoiceChips(listOf(32, 48, 64, 96, 128, 160, 192, 256, 320), a.bitrateKbps, { "$it kbps" }, { k -> vm.edit { it.copy(audio = it.audio.copy(bitrateKbps = k)) } })
            }
        }
        SwitchRow(stringResource(R.string.passthrough), a.passthroughWhenPossible, { b -> vm.edit { it.copy(audio = it.audio.copy(passthroughWhenPossible = b)) } },
            subtitle = stringResource(R.string.passthrough_desc))
        if (s.tier != SettingsTier.BASIC) {
            DropdownSetting(stringResource(R.string.channels), ChannelChoice.entries, a.channels, { Labels.channels(it) }, { ch -> vm.edit { it.copy(audio = it.audio.copy(channels = ch)) } })
            DropdownSetting(stringResource(R.string.sample_rate), listOf(0, 44100, 48000, 96000), a.sampleRate, { if (it == 0) "Source" else "${it / 1000.0} kHz" }, { r -> vm.edit { it.copy(audio = it.audio.copy(sampleRate = r)) } })
            Text(stringResource(R.string.audio_tracks), style = MaterialTheme.typography.labelLarge)
            val selected = com.kuyamcliff.compressor.domain.Planner().selectedAudioStreams(a, info).toSet()
            info.audioStreams.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(st.index in selected, { b ->
                        val now = if (b) selected + st.index else selected - st.index
                        vm.edit { it.copy(audio = it.audio.copy(tracks = info.audioStreams.filter { x -> x.index in now }.map { x -> AudioTrackSettings(x.index, isDefault = x.index == now.minOrNull()) })) }
                    })
                    Text("${st.displayLanguage} · ${st.codec.uppercase()} · ${st.audio?.channelLayout.orEmpty()}" + if (st.title.isNotBlank()) " · ${st.title}" else "")
                }
            }
        }
        s.resolved?.summary?.budget?.let { b -> if (b.audioShare > 0.15) Notice(NoticeKind.INFO, stringResource(R.string.audio_budget_share, "%.0f".format(b.audioShare * 100))) }
    }
}

// --------------------------------------------------------------- crop/scale

@Composable
private fun CropSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    val src = s.current?.info?.video?.video ?: return
    if (v.mode == VideoMode.COPY) return
    SectionCard(stringResource(R.string.sec_crop)) {
        ChoiceChips(listOf(CropMode.NONE, CropMode.MANUAL), v.crop.mode, { stringResource(if (it == CropMode.NONE) R.string.none else R.string.manual) }, { m -> vm.edit { it.v { x -> x.copy(crop = x.crop.copy(mode = m)) } } })
        if (v.crop.mode == CropMode.MANUAL) {
            val o = Geometry.orientedSize(src, v.filters.rotate)
            listOf("top", "bottom", "left", "right").chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { side ->
                        val cur = when (side) { "top" -> v.crop.top; "bottom" -> v.crop.bottom; "left" -> v.crop.left; else -> v.crop.right }
                        var t by remember { mutableStateOf(cur.toString()) }
                        NumberField(side.replaceFirstChar { it.uppercase() }, t, { x ->
                            t = x
                            val n = x.toIntOrNull() ?: 0
                            vm.edit { c -> c.v { y ->
                                val cr = when (side) { "top" -> y.crop.copy(top = n); "bottom" -> y.crop.copy(bottom = n); "left" -> y.crop.copy(left = n); else -> y.crop.copy(right = n) }
                                y.copy(crop = Geometry.clampCrop(src, y, cr))
                            } }
                        }, Modifier.weight(1f), suffix = "px")
                    }
                }
            }
            LabelValue(stringResource(R.string.after_crop), "${o.width - v.crop.left - v.crop.right}×${o.height - v.crop.top - v.crop.bottom}")
            Text(stringResource(R.string.crop_preview_hint), style = MaterialTheme.typography.bodySmall)
        }
        DropdownSetting(stringResource(R.string.scaler), Scaler.entries, v.scaler, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { sc -> vm.edit { it.v { x -> x.copy(scaler = sc) } } })
        ChoiceChips(listOf(0, 90, 180, 270), v.filters.rotate, { "$it°" }, { r -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(rotate = r)) } } })
        SwitchRow(stringResource(R.string.flip_horizontal), v.filters.hflip, { b -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(hflip = b)) } } })
        SwitchRow(stringResource(R.string.flip_vertical), v.filters.vflip, { b -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(vflip = b)) } } })
    }
}

// ------------------------------------------------------------------ filters

@Composable
private fun FilterSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    if (v.mode == VideoMode.COPY) return
    val f = v.filters
    SectionCard(stringResource(R.string.sec_filters), subtitle = stringResource(R.string.filters_cpu_note), trailing = { TextButton(onClick = { vm.resetSection("filters") }) { Text(stringResource(R.string.reset)) } }) {
        DropdownSetting(stringResource(R.string.deinterlace), Deinterlace.entries, f.deinterlace, { it.name.lowercase() }, { d -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(deinterlace = d)) } } })
        SwitchRow(stringResource(R.string.detelecine), f.detelecine, { b -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(detelecine = b)) } } })
        SettingHeader(stringResource(R.string.denoise), stringResource(R.string.help_denoise))
        ChoiceChips(FilterStrength.entries, f.denoise, { Labels.strength(it) }, { st -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(denoise = st)) } } })
        if (f.denoise != FilterStrength.OFF) {
            ChoiceChips(Denoiser.entries, f.denoiser, { it.ff }, { d -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(denoiser = d)) } } })
            Text(stringResource(when (f.denoiser) { Denoiser.ATADENOISE -> R.string.impact_low; Denoiser.NLMEANS -> R.string.impact_high; Denoiser.BM3D -> R.string.impact_very_high }), style = MaterialTheme.typography.bodySmall)
        }
        SettingHeader(stringResource(R.string.deblock))
        ChoiceChips(FilterStrength.entries, f.deblock, { Labels.strength(it) }, { st -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(deblock = st)) } } })
        SettingHeader(stringResource(R.string.sharpen))
        ChoiceChips(FilterStrength.entries, f.sharpen, { Labels.strength(it) }, { st -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(sharpen = st)) } } })
        if (f.sharpen != FilterStrength.OFF) ChoiceChips(Sharpener.entries, f.sharpener, { it.ff }, { d -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(sharpener = d)) } } })
        SwitchRow(stringResource(R.string.deband), f.deband, { b -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(deband = b)) } } }, subtitle = stringResource(R.string.deband_desc))
        SwitchRow(stringResource(R.string.grayscale), f.grayscale, { b -> vm.edit { it.v { x -> x.copy(filters = x.filters.copy(grayscale = b)) } } })
        if (f.needsCpu) Notice(NoticeKind.INFO, stringResource(R.string.filters_disable_fast_path))
    }
}

// ---------------------------------------------------------------------- hdr

@Composable
private fun HdrSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    val src = s.current?.info?.video?.video ?: return
    if (v.mode == VideoMode.COPY) return
    SectionCard(stringResource(R.string.sec_hdr), subtitle = stringResource(R.string.source_is, "${src.hdr.uppercase()} · ${src.bitDepth}-bit")) {
        if (src.isHdr) {
            ChoiceChips(HdrMode.entries, v.hdr, { stringResource(if (it == HdrMode.PRESERVE) R.string.preserve_hdr else R.string.convert_sdr) }, { m -> vm.edit { it.v { x -> x.copy(hdr = m) } } })
            if (v.hdr == HdrMode.TONEMAP_SDR) DropdownSetting(stringResource(R.string.tonemap_algorithm), listOf("hable", "mobius", "reinhard", "clip", "linear"), v.tonemap, { it }, { t -> vm.edit { it.v { x -> x.copy(tonemap = t) } } })
        }
        ChoiceChips(BitDepth.entries, v.bitDepth, { stringResource(when (it) { BitDepth.AUTO -> R.string.automatic; BitDepth.EIGHT -> R.string.eight_bit; BitDepth.TEN -> R.string.ten_bit }) },
            { b -> vm.edit { it.v { x -> x.copy(bitDepth = b) } } },
            disabledReason = { b -> if (b == BitDepth.TEN && !Codecs.info(v.codec).supports10Bit) stringResource(R.string.ten_bit_unavailable) else null })
        if (s.tier == SettingsTier.EXPERT || s.search.isNotBlank()) {
            Text(stringResource(R.string.color_overrides), style = MaterialTheme.typography.labelLarge)
            val prim = listOf<String?>(null, "bt709", "bt2020", "smpte170m", "bt470bg")
            val trc = listOf<String?>(null, "bt709", "smpte2084", "arib-std-b67", "smpte170m", "iec61966-2-1")
            val mat = listOf<String?>(null, "bt709", "bt2020nc", "smpte170m", "bt470bg")
            val rng = listOf<String?>(null, "tv", "pc")
            DropdownSetting(stringResource(R.string.color_primaries), prim, v.color.primaries, { it ?: "Preserve" }, { p -> vm.edit { it.v { x -> x.copy(color = x.color.copy(primaries = p)) } } })
            DropdownSetting(stringResource(R.string.transfer), trc, v.color.transfer, { it ?: "Preserve" }, { p -> vm.edit { it.v { x -> x.copy(color = x.color.copy(transfer = p)) } } })
            DropdownSetting(stringResource(R.string.matrix), mat, v.color.matrix, { it ?: "Preserve" }, { p -> vm.edit { it.v { x -> x.copy(color = x.color.copy(matrix = p)) } } })
            DropdownSetting(stringResource(R.string.range), rng, v.color.range, { it ?: "Preserve" }, { p -> vm.edit { it.v { x -> x.copy(color = x.color.copy(range = p)) } } })
        }
    }
}

// --------------------------------------------------------------- subtitles

@Composable
private fun SubtitleSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val sub = s.config.subtitles
    val info = s.current?.info ?: return
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching { com.kuyamcliff.compressor.data.storage.SourceAccess(context).meta(uri).displayName }.getOrDefault("subtitles.srt")
            vm.edit { it.copy(subtitles = it.subtitles.copy(external = it.subtitles.external + ExternalSubtitle(uri.toString(), name))) }
        }
    }
    SectionCard(stringResource(R.string.sec_subtitles)) {
        ChoiceChips(SubtitleMode.entries, sub.mode, { stringResource(when (it) { SubtitleMode.NONE -> R.string.none; SubtitleMode.COPY -> R.string.copy_subs; SubtitleMode.BURN -> R.string.burn_in }) },
            { m -> vm.edit { it.copy(subtitles = it.subtitles.copy(mode = m)) } })
        when (sub.mode) {
            SubtitleMode.COPY -> {
                val selected = sub.tracks?.filter { it.include }?.map { it.sourceIndex }?.toSet() ?: info.subtitleStreams.map { it.index }.toSet()
                info.subtitleStreams.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(st.index in selected, { b ->
                            val now = if (b) selected + st.index else selected - st.index
                            vm.edit { it.copy(subtitles = it.subtitles.copy(tracks = info.subtitleStreams.map { x -> SubtitleTrackSettings(x.index, include = x.index in now, isDefault = x.default, forced = x.forced) })) }
                        })
                        Text("${st.displayLanguage} · ${st.codec}" + (if (st.forced) " · forced" else "") + (if (st.default) " · default" else ""))
                    }
                }
            }
            SubtitleMode.BURN -> {
                if (info.subtitleStreams.isNotEmpty()) {
                    DropdownSetting(stringResource(R.string.track_to_burn), info.subtitleStreams.map { it.index }, sub.burnStreamIndex ?: info.subtitleStreams.first().index,
                        { i -> info.stream(i)?.let { "${it.displayLanguage} · ${it.codec}" } ?: "$i" }, { i -> vm.edit { it.copy(subtitles = it.subtitles.copy(burnStreamIndex = i, burnExternal = false)) } })
                }
                if (sub.external.isNotEmpty()) SwitchRow(stringResource(R.string.burn_external), sub.burnExternal, { b -> vm.edit { it.copy(subtitles = it.subtitles.copy(burnExternal = b)) } })
                Text(stringResource(R.string.burn_explain), style = MaterialTheme.typography.bodySmall)
            }
            SubtitleMode.NONE -> Unit
        }
        sub.external.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.displayName, Modifier.weight(1f))
                TextButton(onClick = { vm.edit { it.copy(subtitles = it.subtitles.copy(external = it.subtitles.external - e)) } }) { Text(stringResource(R.string.remove)) }
            }
        }
        TextButton(onClick = { picker.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream")) }) { Text(stringResource(R.string.add_subtitle_file)) }
    }
}

@Composable
private fun ChapterSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val ch = s.config.chapters
    val info = s.current?.info ?: return
    SectionCard(stringResource(R.string.chapters), subtitle = stringResource(R.string.n_chapters_found, info.format.chapters.size)) {
        ChoiceChips(ChapterMode.entries, ch.mode, { stringResource(when (it) { ChapterMode.PRESERVE -> R.string.preserve; ChapterMode.STRIP -> R.string.remove; ChapterMode.CUSTOM -> R.string.edit }) },
            { m -> vm.edit { it.copy(chapters = it.chapters.copy(mode = m, custom = if (m == ChapterMode.CUSTOM && it.chapters.custom.isEmpty()) info.format.chapters.map { c -> ChapterEdit(c.title, c.startUs - info.format.startTimeUs, c.endUs - info.format.startTimeUs) } else it.chapters.custom)) } })
        if (ch.mode == ChapterMode.CUSTOM) {
            ch.custom.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var t by remember(i) { mutableStateOf(c.title) }
                    OutlinedTextField(t, { x -> t = x; vm.edit { cfg -> cfg.copy(chapters = cfg.chapters.copy(custom = cfg.chapters.custom.mapIndexed { j, e -> if (j == i) e.copy(title = x) else e })) } },
                        label = { Text(com.kuyamcliff.compressor.util.Format.duration(c.startUs / 1000)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.edit { cfg -> cfg.copy(chapters = cfg.chapters.copy(custom = cfg.chapters.custom.filterIndexed { j, _ -> j != i })) } }) { Text("✕") }
                    if (i > 0) TextButton(onClick = { vm.edit { cfg -> cfg.copy(chapters = cfg.chapters.copy(custom = swapTitles(cfg.chapters.custom, i, i - 1))) } }) { Text("↑") }
                }
            }
        }
    }
}

/** Reordering chapters swaps their titles; times stay in order (containers require ordered chapters). */
private fun swapTitles(list: List<ChapterEdit>, a: Int, b: Int): List<ChapterEdit> {
    val m = list.toMutableList()
    val ta = m[a].title
    m[a] = m[a].copy(title = m[b].title)
    m[b] = m[b].copy(title = ta)
    return m
}

@Composable
private fun MetadataSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val md = s.config.metadata
    val info = s.current?.info ?: return
    SectionCard(stringResource(R.string.metadata)) {
        ChoiceChips(MetadataMode.entries, md.mode, { stringResource(when (it) { MetadataMode.PRESERVE -> R.string.preserve; MetadataMode.STRIP -> R.string.strip; MetadataMode.CUSTOM -> R.string.edit }) },
            { m -> vm.edit { it.copy(metadata = it.metadata.copy(mode = m, fields = if (m == MetadataMode.CUSTOM && it.metadata.fields.isEmpty()) info.format.tags.filterKeys { k -> k in FIELDS } else it.metadata.fields)) } })
        if (md.mode == MetadataMode.CUSTOM) {
            FIELDS.forEach { key ->
                var t by remember(key) { mutableStateOf(md.fields[key].orEmpty()) }
                OutlinedTextField(t, { x -> t = x.take(500); vm.edit { cfg -> cfg.copy(metadata = cfg.metadata.copy(fields = cfg.metadata.fields + (key to t))) } },
                    label = { Text(key.replaceFirstChar { it.uppercase() }) }, singleLine = key != "description", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private val FIELDS = listOf("title", "artist", "album", "genre", "date", "comment", "description")

@Composable
private fun QueueOptionsSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    SectionCard(stringResource(R.string.queue_options)) {
        SettingHeader(stringResource(R.string.priority))
        PriorityChooser(s.priority, vm::setPriority)
        SwitchRow(stringResource(R.string.replace_original), s.config.output.replaceOriginal, { b -> vm.edit { it.copy(output = it.output.copy(replaceOriginal = b)) } },
            subtitle = stringResource(R.string.replace_original_desc))
    }
}

// ------------------------------------------------------------------- expert

@Composable
private fun ExpertSection(s: ConfigureUiState, vm: ConfigureViewModel) {
    val v = s.config.video
    if (v.mode == VideoMode.COPY) return
    val sw = Codecs.info(v.codec)
    val hw = s.resolved?.summary?.pipeline.let { it == com.kuyamcliff.compressor.domain.PipelineKind.HARDWARE || it == com.kuyamcliff.compressor.domain.PipelineKind.HYBRID }
    SectionCard(stringResource(R.string.sec_expert), subtitle = if (hw) stringResource(R.string.expert_hw_note) else sw.displayName,
        trailing = { TextButton(onClick = { vm.resetSection("expert") }) { Text(stringResource(R.string.reset)) } }) {
        if (!hw) {
            if (sw.scale.label != "—" && v.rateControl == RateControlMode.CONSTANT_QUALITY) {
                LabeledSlider("${sw.scale.label} (${stringResource(R.string.encoder_native)})", (v.nativeQuality ?: sw.scale.fromSlider(v.effectiveSlider)).toFloat(),
                    { f -> vm.editLive { it.v { x -> x.copy(nativeQuality = f.roundToInt().toDouble()) } } },
                    "${(v.nativeQuality ?: sw.scale.fromSlider(v.effectiveSlider)).roundToInt()}", range = sw.scale.min.toFloat()..sw.scale.max.toFloat(),
                    onChangeFinished = vm::commit)
            }
            if (sw.presets.isNotEmpty()) DropdownSetting(stringResource(R.string.encoder_preset), listOf<String?>(null) + sw.presets, v.encoderPreset, { it ?: "Default (${sw.defaultPreset})" }, { p -> vm.edit { it.v { x -> x.copy(encoderPreset = p) } } })
            if (sw.tunes.isNotEmpty()) DropdownSetting(stringResource(R.string.tune), listOf<String?>(null) + sw.tunes, v.tune, { it ?: "None" }, { p -> vm.edit { it.v { x -> x.copy(tune = p) } } })
            else Text(stringResource(R.string.no_tunes, sw.displayName), style = MaterialTheme.typography.bodySmall)
            if (sw.profiles.isNotEmpty()) DropdownSetting(stringResource(R.string.profile), listOf<String?>(null) + sw.profiles, v.profile, { it ?: "Auto" }, { p -> vm.edit { it.v { x -> x.copy(profile = p) } } })
        }
        var lvl by remember { mutableStateOf(v.level.orEmpty()) }
        OutlinedTextField(lvl, { t -> lvl = t.take(5); vm.edit { it.v { x -> x.copy(level = lvl.ifBlank { null }) } } }, label = { Text(stringResource(R.string.level)) }, singleLine = true)
        var gop by remember { mutableStateOf(if (v.keyframeIntervalSec > 0) trimNum(v.keyframeIntervalSec) else "") }
        NumberField(stringResource(R.string.keyframe_interval), gop, { t -> gop = t; vm.edit { it.v { x -> x.copy(keyframeIntervalSec = t.toDoubleOrNull() ?: 0.0) } } }, suffix = "s", decimal = true,
            supporting = stringResource(R.string.keyframe_help))
        if (hw || sw.supportsBFrames) {
            var bf by remember { mutableStateOf(if (v.bFrames >= 0) v.bFrames.toString() else "") }
            NumberField(stringResource(R.string.b_frames), bf, { t -> bf = t; vm.edit { it.v { x -> x.copy(bFrames = t.toIntOrNull()?.coerceIn(0, 16) ?: -1) } } }, supporting = stringResource(R.string.help_bframes))
        } else Text(stringResource(R.string.bframes_unavailable, sw.displayName), style = MaterialTheme.typography.bodySmall)
        if (!hw) {
            var refs by remember { mutableStateOf(if (v.refFrames > 0) v.refFrames.toString() else "") }
            NumberField(stringResource(R.string.reference_frames), refs, { t -> refs = t; vm.edit { it.v { x -> x.copy(refFrames = t.toIntOrNull()?.coerceIn(1, 16) ?: -1) } } })
            var thr by remember { mutableStateOf(if (v.threads > 0) v.threads.toString() else "") }
            NumberField(stringResource(R.string.threads), thr, { t -> thr = t; vm.edit { it.v { x -> x.copy(threads = t.toIntOrNull()?.coerceIn(0, 64) ?: 0) } } }, supporting = stringResource(R.string.threads_help))
            var maxr by remember { mutableStateOf(if (v.maxBitrateKbps > 0) v.maxBitrateKbps.toString() else "") }
            NumberField(stringResource(R.string.max_bitrate_vbv), maxr, { t -> maxr = t; vm.edit { it.v { x -> x.copy(maxBitrateKbps = t.toIntOrNull() ?: 0) } } }, suffix = "kbps")
            var opts by remember { mutableStateOf(v.advancedOptions.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
            OutlinedTextField(
                opts,
                { t ->
                    opts = t.take(2000)
                    val map = opts.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
                        val i = line.indexOf('='); if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else line to ""
                    }
                    vm.edit { it.v { x -> x.copy(advancedOptions = map) } }
                },
                label = { Text(stringResource(R.string.advanced_options)) },
                supportingText = { Text(s.optionError ?: stringResource(R.string.advanced_options_help)) },
                isError = s.optionError != null,
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("advanced_options"),
            )
        }
    }
}

@Composable
private fun HardwareSection(s: ConfigureUiState) {
    val sum = s.resolved?.summary ?: return
    SectionCard(stringResource(R.string.sec_hardware_info)) {
        LabelValue(stringResource(R.string.encoder), sum.encoderDisplay, mono = true)
        LabelValue(stringResource(R.string.codec_component), sum.hwEncoderComponent ?: "—", mono = true)
        LabelValue(stringResource(R.string.decoder_component), sum.hwDecoderComponent ?: "—", mono = true)
        LabelValue(stringResource(R.string.input), sum.inputMode ?: "—")
        s.resolved.plan.video?.let { v ->
            LabelValue(stringResource(R.string.bit_depth), "${v.bitDepth}-bit")
            LabelValue(stringResource(R.string.resolution), "${v.width}×${v.height}")
            LabelValue("FPS", com.kuyamcliff.compressor.util.Format.fpsLabel(sum.outputFps))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sum.hardwareUnavailableReasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
