package com.kuyamcliff.compressor.ui.configure

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.data.repo.AppJson
import com.kuyamcliff.compressor.data.repo.withoutPrivatePaths
import com.kuyamcliff.compressor.domain.BuiltInPresets
import com.kuyamcliff.compressor.domain.ConfigFix
import com.kuyamcliff.compressor.domain.ConfigIssue
import com.kuyamcliff.compressor.domain.ConfigValidator
import com.kuyamcliff.compressor.domain.FpsSuggestion
import com.kuyamcliff.compressor.domain.PlanningException
import com.kuyamcliff.compressor.domain.Preset
import com.kuyamcliff.compressor.domain.Recommendation
import com.kuyamcliff.compressor.domain.Recommendations
import com.kuyamcliff.compressor.domain.ResolvedPlan
import com.kuyamcliff.compressor.domain.Severity
import com.kuyamcliff.compressor.domain.TargetSize
import com.kuyamcliff.compressor.engine.ComplexityReport
import com.kuyamcliff.compressor.engine.EngineError
import com.kuyamcliff.compressor.engine.EngineFailure
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.JobPriority
import com.kuyamcliff.compressor.model.PlanSegment
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.SourceFile
import com.kuyamcliff.compressor.preview.PreviewPosition
import com.kuyamcliff.compressor.preview.PreviewResult
import com.kuyamcliff.compressor.queue.JobFactory
import com.kuyamcliff.compressor.ui.navigation.ConfigureRequest
import com.kuyamcliff.compressor.ui.navigation.EntryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsTier { BASIC, ADVANCED, EXPERT }

data class SourceState(
    val uri: Uri,
    val file: SourceFile? = null,
    val error: EngineError? = null,
    val loading: Boolean = true,
    val thumbnail: Bitmap? = null,
)

sealed interface PreviewState {
    data object Idle : PreviewState
    data class Running(val progress: Double) : PreviewState
    data class Done(val result: PreviewResult, val planKey: String) : PreviewState
    data class Failed(val error: EngineError) : PreviewState
}

/** Quick compare / "smallest file that still looks good" experiment (PRD §105, §150, §151). */
sealed interface LadderState {
    data object Idle : LadderState
    data class Running(val index: Int, val total: Int, val progress: Double) : LadderState
    data class Done(val points: List<com.kuyamcliff.compressor.preview.ComparisonPoint>, val segmentStartUs: Long) : LadderState
    data class Failed(val message: String) : LadderState
}

sealed interface StartState {
    data object Idle : StartState
    data class ConfirmDanger(val items: List<String>) : StartState
    data class NameConflict(val name: String) : StartState
    data class Calibrating(val step: Int, val total: Int) : StartState
    data class Started(val count: Int) : StartState
    data class Failed(val message: String) : StartState
}

data class ConfigureUiState(
    val sources: List<SourceState> = emptyList(),
    val selected: Int = 0,
    val config: CompressionConfig = BuiltInPresets.default.config,
    val presetId: String? = BuiltInPresets.default.id,
    val presetName: String? = BuiltInPresets.default.name,
    val tier: SettingsTier = SettingsTier.BASIC,
    val search: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val planning: Boolean = false,
    val resolved: ResolvedPlan? = null,
    val issues: List<ConfigIssue> = emptyList(),
    val recommendation: Recommendation? = null,
    val smartFps: FpsSuggestion? = null,
    val complexity: ComplexityReport? = null,
    val analyzingComplexity: Boolean = false,
    val preview: PreviewState = PreviewState.Idle,
    val previewSeconds: Int = 10,
    val previewPosition: PreviewPosition = PreviewPosition.MIDDLE,
    val playerPositionUs: Long = 0,
    val start: StartState = StartState.Idle,
    val entryMode: EntryMode = EntryMode.NORMAL,
    val makeSmallerPercent: Int = 25,
    val batchPerFile: Boolean = true,
    val optionError: String? = null,
    val hardwareProblems: List<String> = emptyList(),
    val calibration: Double = 1.0,
    val priority: JobPriority = JobPriority.NORMAL,
    val expertMode: Boolean = false,
    val ladder: LadderState = LadderState.Idle,
) {
    val current: SourceFile? get() = sources.getOrNull(selected)?.file
    val errors: List<ConfigIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val isBatch: Boolean get() = sources.size > 1
    val readyCount: Int get() = sources.count { it.file != null }
}

class ConfigureViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CompressorApp).container
    private val _state = MutableStateFlow(ConfigureUiState())
    val state: StateFlow<ConfigureUiState> = _state.asStateFlow()

    private val undo = ArrayDeque<CompressionConfig>()
    private val redo = ArrayDeque<CompressionConfig>()
    private var baseline: CompressionConfig = BuiltInPresets.default.config
    private var planJob: Job? = null
    private var previewJob: Job? = null
    private var handledRequest: Long = -1

    fun load(request: ConfigureRequest) {
        if (request.requestId == handledRequest) return
        handledRequest = request.requestId
        viewModelScope.launch {
            val prefs = c.preferences.current()
            val last = if (prefs.useLastSettings) c.settings.lastConfig() else null
            val preset = (request.presetId ?: prefs.defaultPresetId).let { c.presets.get(it) } ?: BuiltInPresets.default
            var config = request.initialConfig ?: last ?: preset.config
            if (request.entryMode == EntryMode.TARGET_SIZE && request.targetSizeMb > 0) {
                config = config.copy(video = config.video.copy(rateControl = RateControlMode.TARGET_SIZE, targetSizeMb = request.targetSizeMb))
            }
            baseline = config
            undo.clear(); redo.clear()
            _state.value = ConfigureUiState(
                sources = request.uris.map { SourceState(it) },
                config = config,
                presetId = if (request.initialConfig == null && last == null) preset.id else null,
                presetName = if (request.initialConfig == null && last == null) preset.name else null,
                entryMode = request.entryMode,
                makeSmallerPercent = request.makeSmallerPercent,
                previewSeconds = prefs.previewSeconds,
                tier = if (prefs.expertControls) SettingsTier.ADVANCED else if (prefs.uiMode.name == "ADVANCED") SettingsTier.ADVANCED else SettingsTier.BASIC,
                expertMode = prefs.expertControls,
            )
            request.uris.forEachIndexed { i, uri -> analyze(i, uri) }
        }
    }

    private suspend fun analyze(index: Int, uri: Uri) {
        try {
            c.sourceAccess.persistPermission(uri)
            val file = c.sources.analyze(uri)
            updateSource(index) { it.copy(file = file, loading = false) }
            val thumb = withContext(Dispatchers.IO) { thumbnail(uri, file) }
            updateSource(index) { it.copy(thumbnail = thumb) }
            if (index == _state.value.selected) onSourceReady()
        } catch (e: EngineFailure) {
            updateSource(index) { it.copy(error = e.error, loading = false) }
        } catch (e: Exception) {
            updateSource(index) { it.copy(error = EngineError(message = e.message ?: "Unable to read video"), loading = false) }
        }
    }

    private fun thumbnail(uri: Uri, file: SourceFile): Bitmap? {
        val at = (file.info.durationUs / 10).coerceAtLeast(0)
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(getApplication(), uri)
                r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)?.let { return it }
            }
        }
        // Formats the platform cannot decode: fall back to the FFmpeg engine.
        return runCatching {
            kotlinx.coroutines.runBlocking { c.sourceAccess.openRead(uri).use { c.engine.thumbnail(it, at, 480) } }
        }.getOrNull()
    }

    private fun updateSource(i: Int, f: (SourceState) -> SourceState) =
        _state.update { s -> s.copy(sources = s.sources.mapIndexed { j, x -> if (j == i) f(x) else x }) }

    private fun onSourceReady() {
        val s = _state.value
        val file = s.current ?: return
        if (s.entryMode == EntryMode.MAKE_SMALLER) {
            val target = TargetSize.makeSmallerTarget(file.sizeBytes, s.makeSmallerPercent) / (1024.0 * 1024)
            setConfig(s.config.copy(video = s.config.video.copy(rateControl = RateControlMode.TARGET_SIZE, targetSizeMb = roundMb(target))), record = false)
            baseline = _state.value.config
        }
        viewModelScope.launch {
            val ctx = c.jobFactory.context(file, s.config)
            val rec = withContext(Dispatchers.Default) { runCatching { Recommendations.recommend(s.config, ctx) }.getOrNull() }
            _state.update { it.copy(recommendation = rec, smartFps = Recommendations.smartFps(file.info), complexity = ctx.complexity) }
        }
        replan()
    }

    fun select(index: Int) {
        _state.update { it.copy(selected = index, preview = PreviewState.Idle) }
        if (_state.value.current != null) onSourceReady()
    }

    fun removeSource(index: Int) {
        _state.update { s ->
            val list = s.sources.filterIndexed { i, _ -> i != index }
            s.copy(sources = list, selected = s.selected.coerceAtMost((list.size - 1).coerceAtLeast(0)))
        }
        replan()
    }

    // ----------------------------------------------------------- config edits

    /** Every edit goes through here: one canonical config, recorded for undo. */
    fun setConfig(next: CompressionConfig, record: Boolean = true) {
        val prev = _state.value.config
        if (next == prev) return
        if (record) {
            undo.addLast(prev)
            if (undo.size > 60) undo.removeFirst()
            redo.clear()
        }
        _state.update { it.copy(config = next, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), presetName = if (record) null else it.presetName) }
        replan()
    }

    fun edit(f: (CompressionConfig) -> CompressionConfig) = setConfig(f(_state.value.config))

    /** Live slider changes are not recorded individually; [commit] records the drag as one step. */
    private var dragStart: CompressionConfig? = null
    fun editLive(f: (CompressionConfig) -> CompressionConfig) {
        if (dragStart == null) dragStart = _state.value.config
        _state.update { it.copy(config = f(it.config)) }
        replan(debounceMs = 250)
    }

    fun commit() {
        val start = dragStart ?: return
        dragStart = null
        val now = _state.value.config
        if (start != now) {
            undo.addLast(start)
            redo.clear()
            _state.update { it.copy(canUndo = true, canRedo = false, presetName = null) }
        }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.config)
        _state.update { it.copy(config = prev, canUndo = undo.isNotEmpty(), canRedo = true) }
        replan()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.config)
        _state.update { it.copy(config = next, canUndo = true, canRedo = redo.isNotEmpty()) }
        replan()
    }

    fun resetAll() = setConfig(baseline)

    /** Reset one section to the preset/baseline values (PRD §58). */
    fun resetSection(section: String) {
        val b = baseline
        edit { cfg ->
            when (section) {
                "video" -> cfg.copy(video = b.video)
                "quality" -> cfg.copy(video = cfg.video.copy(rateControl = b.video.rateControl, qualityLevel = b.video.qualityLevel, qualitySlider = b.video.qualitySlider, nativeQuality = null, bitrateKbps = b.video.bitrateKbps, targetSizeMb = b.video.targetSizeMb, twoPass = b.video.twoPass))
                "resolution" -> cfg.copy(video = cfg.video.copy(resolution = b.video.resolution, customWidth = b.video.customWidth, customHeight = b.video.customHeight, crop = b.video.crop, scaler = b.video.scaler))
                "fps" -> cfg.copy(video = cfg.video.copy(fps = b.video.fps, fpsMode = b.video.fpsMode, customFps = b.video.customFps))
                "filters" -> cfg.copy(video = cfg.video.copy(filters = b.video.filters))
                "hdr" -> cfg.copy(video = cfg.video.copy(hdr = b.video.hdr, bitDepth = b.video.bitDepth, color = b.video.color))
                "expert" -> cfg.copy(video = cfg.video.copy(profile = b.video.profile, level = b.video.level, encoderPreset = b.video.encoderPreset, tune = b.video.tune, keyframeIntervalSec = b.video.keyframeIntervalSec, bFrames = b.video.bFrames, refFrames = b.video.refFrames, threads = b.video.threads, advancedOptions = b.video.advancedOptions))
                "audio" -> cfg.copy(audio = b.audio)
                "subtitles" -> cfg.copy(subtitles = b.subtitles)
                "chapters" -> cfg.copy(chapters = b.chapters)
                "metadata" -> cfg.copy(metadata = b.metadata)
                "output" -> cfg.copy(container = b.container, output = b.output)
                else -> cfg
            }
        }
    }

    fun applyPreset(p: Preset) {
        val cur = _state.value.config
        // Keep source-specific choices (tracks, crop pixels, output folder).
        val merged = p.config.copy(
            audio = p.config.audio.copy(tracks = cur.audio.tracks),
            subtitles = p.config.subtitles.copy(tracks = cur.subtitles.tracks, external = cur.subtitles.external),
            output = p.config.output.copy(folderUri = cur.output.folderUri),
        )
        baseline = merged
        setConfig(merged)
        _state.update { it.copy(presetId = p.id, presetName = p.name) }
    }

    fun applyFix(fix: ConfigFix) = edit(fix.apply)
    fun applyRecommendation() { _state.value.recommendation?.let { setConfig(it.config) } }

    fun setTier(t: SettingsTier) = _state.update { it.copy(tier = t) }
    fun setSearch(q: String) = _state.update { it.copy(search = q) }
    fun setPriority(p: JobPriority) = _state.update { it.copy(priority = p) }
    fun setBatchPerFile(v: Boolean) = _state.update { it.copy(batchPerFile = v) }
    fun setPlayerPosition(us: Long) = _state.update { it.copy(playerPositionUs = us) }
    fun setPreviewOptions(seconds: Int? = null, position: PreviewPosition? = null) =
        _state.update { it.copy(previewSeconds = seconds ?: it.previewSeconds, previewPosition = position ?: it.previewPosition) }

    fun setMakeSmaller(percent: Int) {
        val file = _state.value.current ?: return
        _state.update { it.copy(makeSmallerPercent = percent) }
        val target = TargetSize.makeSmallerTarget(file.sizeBytes, percent) / (1024.0 * 1024)
        edit { it.copy(video = it.video.copy(rateControl = RateControlMode.TARGET_SIZE, targetSizeMb = roundMb(target))) }
    }

    private fun roundMb(v: Double) = if (v >= 100) Math.round(v).toDouble() else Math.round(v * 10) / 10.0

    fun analyzeComplexity() {
        val file = _state.value.current ?: return
        _state.update { it.copy(analyzingComplexity = true) }
        viewModelScope.launch {
            val report = runCatching { c.sources.complexity(Uri.parse(file.uri)) }.getOrNull()
            _state.update { it.copy(complexity = report, analyzingComplexity = false) }
            replan()
        }
    }

    // --------------------------------------------------------------- planning

    private fun replan(debounceMs: Long = 120) {
        planJob?.cancel()
        planJob = viewModelScope.launch {
            delay(debounceMs)
            val s = _state.value
            val file = s.current ?: return@launch
            _state.update { it.copy(planning = true) }
            val preview = s.preview as? PreviewState.Done
            val ctx = c.jobFactory.context(
                file, s.config, complexity = s.complexity,
                measuredVideoBps = null, calibration = s.calibration,
            )
            val result = withContext(Dispatchers.Default) {
                val issues = ConfigValidator.validate(s.config, ctx)
                val hwProblems = ConfigValidator.hardwareProblems(s.config, ctx)
                val resolved = if (issues.none { it.severity == Severity.ERROR }) runCatching { c.jobFactory.plan(s.config, ctx) }.getOrNull() else null
                Triple(issues, resolved, hwProblems)
            }
            // Re-plan with the measured sample bitrate when a preview with identical settings exists.
            var resolved = result.second
            if (resolved != null && preview != null && s.config.video.rateControl == RateControlMode.CONSTANT_QUALITY) {
                val previewKey = resolved.plan.forKey(preview.result.segmentStartUs, preview.result.segmentDurationUs)
                if (previewKey == preview.planKey) {
                    val ctx2 = ctx.copy(measuredVideoBps = preview.result.videoBps)
                    resolved = runCatching { c.jobFactory.plan(s.config, ctx2) }.getOrDefault(resolved)
                }
            }
            val invalidatePreview = preview != null && resolved != null &&
                resolved.plan.forKey(preview.result.segmentStartUs, preview.result.segmentDurationUs) != preview.planKey
            _state.update {
                it.copy(
                    planning = false, issues = result.first, resolved = resolved, hardwareProblems = result.third,
                    preview = if (invalidatePreview) PreviewState.Idle else it.preview,
                )
            }
            validateExpertOptions()
        }
    }

    private suspend fun validateExpertOptions() {
        val s = _state.value
        val opts = s.config.video.advancedOptions
        val encoder = s.resolved?.plan?.video?.encoder
        if (opts.isEmpty() || encoder == null || s.resolved.plan.video.pipeline != "software") {
            _state.update { it.copy(optionError = null) }
            return
        }
        val err = runCatching { c.engine.validateEncoderOptions(encoder, opts) }.getOrElse { it.message }
        _state.update { it.copy(optionError = err) }
    }

    // ---------------------------------------------------------------- preview

    fun runPreview() {
        val s = _state.value
        val file = s.current ?: return
        val seg = c.previews.segmentFor(s.previewPosition, file.info.durationUs, s.previewSeconds, s.playerPositionUs, s.playerPositionUs)
        previewJob?.cancel()
        _state.update { it.copy(preview = PreviewState.Running(0.0)) }
        previewJob = viewModelScope.launch {
            try {
                val r = c.previews.encode(file, s.config, seg, withMetrics = true, calibration = s.calibration) { p ->
                    _state.update { it.copy(preview = PreviewState.Running(p)) }
                }
                val key = _state.value.resolved?.plan?.forKey(seg.startUs, r.segmentDurationUs) ?: r.key
                _state.update { it.copy(preview = PreviewState.Done(r, key)) }
                replan(0)
            } catch (e: EngineFailure) {
                _state.update { it.copy(preview = PreviewState.Failed(e.error)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(preview = PreviewState.Failed(EngineError(message = e.message ?: "Preview failed"))) }
            }
        }
    }

    fun cancelPreview() {
        previewJob?.cancel()
        _state.update { it.copy(preview = PreviewState.Idle) }
    }

    // ------------------------------------------------------------------ start

    fun requestStart(confirmedDanger: Boolean = false, decision: JobFactory.NameDecision? = null) {
        val s = _state.value
        val file = s.current ?: return
        val resolved = s.resolved ?: return
        viewModelScope.launch {
            if (!confirmedDanger) {
                val ctx = c.jobFactory.context(file, s.config)
                val dangers = ConfigValidator.dangerousSettings(s.config, resolved.summary, ctx)
                if (dangers.isNotEmpty()) {
                    _state.update { it.copy(start = StartState.ConfirmDanger(dangers)) }
                    return@launch
                }
            }
            if (!s.isBatch && decision == null && s.config.output.conflict == com.kuyamcliff.compressor.model.ConflictPolicy.ASK) {
                val name = c.jobFactory.outputName(file, s.config, resolved)
                if (c.jobFactory.nameExists(name, s.config)) {
                    _state.update { it.copy(start = StartState.NameConflict(name)) }
                    return@launch
                }
            }
            enqueueAll(decision ?: JobFactory.NameDecision.USE)
        }
    }

    private suspend fun enqueueAll(decision: JobFactory.NameDecision) {
        val s = _state.value
        val prefs = c.preferences.current()
        try {
            var calibration = s.calibration
            val primary = s.current!!
            if (s.config.video.rateControl == RateControlMode.TARGET_SIZE && s.config.video.smartTarget && !s.isBatch) {
                val total = prefs.smartTargetIterations
                _state.update { it.copy(start = StartState.Calibrating(0, total)) }
                calibration = c.previews.calibrateTarget(primary, s.config, total) { step, _ ->
                    _state.update { it.copy(start = StartState.Calibrating(step, total)) }
                }
                _state.update { it.copy(calibration = calibration) }
            }
            var count = 0
            for (src in s.sources.mapNotNull { it.file }) {
                val cfg = perFileConfig(src, s)
                val ctx = c.jobFactory.context(src, cfg, calibration = if (src.uri == primary.uri) calibration else 1.0)
                val resolved = try {
                    c.jobFactory.plan(cfg, ctx)
                } catch (e: PlanningException) {
                    if (!s.isBatch) throw e else continue
                }
                c.jobFactory.enqueue(src, cfg, resolved, decision, s.priority, s.presetName)
                count++
            }
            c.settings.saveLastConfig(s.config)
            c.queue.kick()
            _state.update { it.copy(start = StartState.Started(count)) }
        } catch (e: PlanningException) {
            _state.update { it.copy(start = StartState.Failed(e.issues.joinToString("\n") { it.message })) }
        } catch (e: EngineFailure) {
            _state.update { it.copy(start = StartState.Failed(e.error.message)) }
        }
    }

    /** Batch "automatic per-file optimisation": per-file size targets for Make Smaller. */
    private fun perFileConfig(src: SourceFile, s: ConfigureUiState): CompressionConfig {
        if (!s.isBatch || !s.batchPerFile) return s.config
        if (s.entryMode == EntryMode.MAKE_SMALLER && s.config.video.rateControl == RateControlMode.TARGET_SIZE) {
            val mb = TargetSize.makeSmallerTarget(src.sizeBytes, s.makeSmallerPercent) / (1024.0 * 1024)
            return s.config.copy(video = s.config.video.copy(targetSizeMb = roundMb(mb)))
        }
        return s.config
    }

    private var ladderJob: Job? = null

    /**
     * Encodes the same short segment at several quality levels with the current
     * codec/resolution and measures real size + PSNR/SSIM for each.
     */
    fun runLadder(levels: List<com.kuyamcliff.compressor.model.QualityLevel>) {
        val s = _state.value
        val file = s.current ?: return
        if (s.config.video.mode != com.kuyamcliff.compressor.model.VideoMode.TRANSCODE) return
        val seg = c.previews.segmentFor(s.previewPosition, file.info.durationUs, s.previewSeconds, s.playerPositionUs, s.playerPositionUs)
        val configs = levels.map { q ->
            q.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } to s.config.copy(
                video = s.config.video.copy(rateControl = RateControlMode.CONSTANT_QUALITY, qualityLevel = q, qualitySlider = q.slider, nativeQuality = null, twoPass = false, smartTarget = false),
            )
        }
        ladderJob?.cancel()
        ladderJob = viewModelScope.launch {
            _state.update { it.copy(ladder = LadderState.Running(0, configs.size, 0.0)) }
            try {
                val points = c.previews.compare(file, configs, seg) { i, p ->
                    _state.update { it.copy(ladder = LadderState.Running(i, configs.size, p)) }
                }
                _state.update { it.copy(ladder = LadderState.Done(points, seg.startUs)) }
            } catch (e: EngineFailure) {
                _state.update { it.copy(ladder = LadderState.Failed(e.error.message)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(ladder = LadderState.Idle) }
                throw e
            }
        }
    }

    fun cancelLadder() { ladderJob?.cancel(); _state.update { it.copy(ladder = LadderState.Idle) } }

    fun applyLadderPoint(p: com.kuyamcliff.compressor.preview.ComparisonPoint) = setConfig(p.config)

    fun dismissStart() = _state.update { it.copy(start = StartState.Idle) }

    fun saveAsPreset(name: String, description: String) {
        viewModelScope.launch {
            val p = c.presets.saveCustom(name, description, _state.value.config.withoutPrivatePaths())
            _state.update { it.copy(presetId = p.id, presetName = p.name) }
        }
    }

    fun exportConfigJson(includeSource: Boolean): String {
        val s = _state.value
        val cfg = AppJson.pretty.encodeToString(CompressionConfig.serializer(), if (includeSource) s.config else s.config.withoutPrivatePaths())
        return buildString {
            append("{\n  \"appVersion\": \"").append(com.kuyamcliff.compressor.BuildConfig.VERSION_NAME).append("\",\n")
            if (includeSource) s.current?.let { append("  \"source\": \"").append(it.displayName.replace("\"", "'")).append("\",\n") }
            append("  \"config\": ").append(cfg.replace("\n", "\n  ")).append("\n}")
        }
    }

    fun technicalPlanJson(): String = _state.value.resolved?.plan?.let {
        AppJson.pretty.encodeToString(com.kuyamcliff.compressor.model.ExecutionPlan.serializer(), it)
    } ?: ""
}

/** Plan identity for a preview segment (used to know when a preview matches the current settings). */
fun com.kuyamcliff.compressor.model.ExecutionPlan.forKey(startUs: Long, durationUs: Long): String =
    copy(segment = PlanSegment(startUs, durationUs), validate = null, jobId = "preview").cacheKey()
