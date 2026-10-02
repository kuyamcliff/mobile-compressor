package com.kuyamcliff.compressor.queue

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kuyamcliff.compressor.data.db.CompressionHistoryEntity
import com.kuyamcliff.compressor.data.db.CompressionJobEntity
import com.kuyamcliff.compressor.data.prefs.HardwareFallback
import com.kuyamcliff.compressor.data.prefs.UserPreferences
import com.kuyamcliff.compressor.data.repo.AppJson
import com.kuyamcliff.compressor.data.repo.HistoryRepository
import com.kuyamcliff.compressor.data.repo.JobRepository
import com.kuyamcliff.compressor.data.storage.AppFiles
import com.kuyamcliff.compressor.data.storage.OutputStorage
import com.kuyamcliff.compressor.data.storage.OutputTarget
import com.kuyamcliff.compressor.data.storage.SourceAccess
import com.kuyamcliff.compressor.engine.EngineError
import com.kuyamcliff.compressor.engine.EngineEvents
import com.kuyamcliff.compressor.engine.EngineFailure
import com.kuyamcliff.compressor.engine.ErrorCategory
import com.kuyamcliff.compressor.engine.MediaEngine
import com.kuyamcliff.compressor.engine.NativeJobHandle
import com.kuyamcliff.compressor.engine.ProgressStats
import com.kuyamcliff.compressor.engine.ValidationResult
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.ExecutionPlan
import com.kuyamcliff.compressor.model.JobStatus
import com.kuyamcliff.compressor.model.JobSummary
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.diagnostics.Breadcrumb
import com.kuyamcliff.compressor.diagnostics.Diagnostics
import com.kuyamcliff.compressor.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** In-memory live state of a job (progress events are too frequent for the database). */
data class LiveJob(
    val id: Long,
    val stats: ProgressStats? = null,
    val pipeline: String? = null,
    val encoder: String? = null,
    val statusText: String? = null,
    val validating: Boolean = false,
)

/** Re-plans a job with a different engine (hardware -> software fallback). */
fun interface Replanner {
    suspend fun replan(job: CompressionJobEntity, engine: EngineChoice): Pair<ExecutionPlan, JobSummary>?
}

/**
 * Owns the compression queue: scheduling (priority, position, concurrency),
 * execution of native jobs, finalisation, history, notifications, thermal and
 * battery policy, and recovery after process death. Runs in the application
 * scope; [EncodingService] keeps the process in the foreground while it works.
 */
class QueueManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val jobs: JobRepository,
    private val history: HistoryRepository,
    private val engine: MediaEngine,
    private val access: SourceAccess,
    private val output: OutputStorage,
    private val files: AppFiles,
    private val prefs: UserPreferences,
    private val monitor: DeviceMonitor,
    private val notifications: Notifications,
    private val replanner: Replanner,
    private val diagnostics: Diagnostics,
) {
    private class Running(val id: Long, val usesHw: Boolean) {
        @Volatile var handle: NativeJobHandle? = null
        @Volatile var userPaused = false
        @Volatile var policyPaused = false
        @Volatile var throttle = 0
        var coroutine: Job? = null
        var target: OutputTarget? = null
    }

    private val running = ConcurrentHashMap<Long, Running>()
    private val scheduleMutex = Mutex()
    private val _live = MutableStateFlow<Map<Long, LiveJob>>(emptyMap())
    val live: StateFlow<Map<Long, LiveJob>> = _live.asStateFlow()
    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()
    private val _policyMessage = MutableStateFlow<String?>(null)
    val policyMessage: StateFlow<String?> = _policyMessage.asStateFlow()
    private var wakeLock: PowerManager.WakeLock? = null
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val recovered = jobs.recoverAfterRestart()
            recovered.forEach { j -> j.outputUri?.takeIf { j.outputKind == "saf" || j.outputKind == "mediastore" }?.let { discardTemp(j) } }
            val keep = running.values.mapNotNull { it.target?.uri?.toString() }.toSet()
            withContext(Dispatchers.IO) { output.cleanupAbandonedPending(keep) }
            combine(jobs.queue, monitor.state, prefs.flow) { q, _, _ -> q }.collect { q ->
                schedule()
                publishSnapshot(q)
            }
        }
        scope.launch { live.collect { publishSnapshot(null) } }
    }

    /** A temp output that belongs to an interrupted job is unreachable now: delete it. */
    private suspend fun discardTemp(j: CompressionJobEntity) = withContext(Dispatchers.IO) {
        val uri = j.outputUri ?: return@withContext
        if (j.status == JobStatus.INTERRUPTED.name) {
            output.discard(OutputTarget(j.outputKind, Uri.parse(uri), j.outputName, j.outputFolderUri, ""))
            jobs.update(j.copy(outputUri = null))
        }
    }

    fun kick() { scope.launch { schedule() } }

    // ------------------------------------------------------------- controls

    suspend fun pause(id: Long) {
        val r = running[id]
        if (r != null) {
            r.userPaused = true
            if (r.handle?.pause() == true || r.policyPaused) {
                jobs.transition(id, JobStatus.PAUSED) { it.copy(statusReason = null) }
            }
        } else {
            jobs.transition(id, JobStatus.PAUSED) { it.copy(statusReason = "Paused before starting") }
        }
        kick()
    }

    suspend fun resume(id: Long) {
        val r = running[id]
        if (r != null) {
            r.userPaused = false
            if (!r.policyPaused && r.handle?.resume() == true) jobs.transition(id, JobStatus.ENCODING) { it.copy(statusReason = null) }
        } else {
            val j = jobs.get(id) ?: return
            if (j.status == JobStatus.PAUSED.name) jobs.transition(id, JobStatus.WAITING) { it.copy(statusReason = null) }
        }
        kick()
    }

    suspend fun cancel(id: Long) {
        val r = running[id]
        if (r != null) {
            r.handle?.cancel()
            // The runner observes CANCELLED from the engine and cleans up.
            if (r.handle == null) r.coroutine?.cancel()
        } else {
            jobs.transition(id, JobStatus.CANCELLED) { it.copy(statusReason = null, completedAt = System.currentTimeMillis()) }
        }
    }

    suspend fun retry(id: Long) {
        jobs.transition(id, JobStatus.WAITING) {
            it.copy(progress = 0f, errorCode = null, errorMessage = null, errorJson = null, statusReason = null, statisticsJson = null, outputUri = null)
        }
        kick()
    }

    suspend fun remove(id: Long) {
        if (running.containsKey(id)) return
        jobs.delete(id)
    }

    suspend fun pauseAll() {
        (running.keys + jobs.waiting().map { it.id }).distinct().forEach { pause(it) }
    }

    suspend fun resumeAll() {
        jobs.inFlight().filter { it.status == JobStatus.PAUSED.name }.forEach { resume(it.id) }
    }

    suspend fun cancelAll() {
        running.keys.toList().forEach { cancel(it) }
        jobs.waiting().forEach { cancel(it.id) }
        jobs.inFlight().filter { it.status == JobStatus.PAUSED.name && !running.containsKey(it.id) }.forEach { cancel(it.id) }
    }

    fun isRunning(id: Long) = running.containsKey(id)
    fun handle(id: Long): NativeJobHandle? = running[id]?.handle

    // ----------------------------------------------------------- scheduling

    private suspend fun schedule() = scheduleMutex.withLock {
        val p = prefs.current()
        val dev = monitor.state.value
        val policy = DeviceMonitor.policy(p.performanceProfile, dev, p.chargingOnly)
        _policyMessage.value = policy.reason

        // Apply policy to running jobs.
        for (r in running.values) {
            val h = r.handle ?: continue
            if (r.throttle != policy.throttlePercent) {
                h.setThrottle(policy.throttlePercent)
                r.throttle = policy.throttlePercent
            }
            if (policy.pause && !r.policyPaused) {
                r.policyPaused = true
                if (h.pause() || r.userPaused) {
                    val st = if (policy.thermal) JobStatus.THERMAL_PAUSED else JobStatus.PAUSED
                    jobs.transition(r.id, st) { it.copy(statusReason = policy.reason) }
                }
            } else if (!policy.pause && r.policyPaused) {
                r.policyPaused = false
                if (!r.userPaused && h.resume()) jobs.transition(r.id, JobStatus.ENCODING) { it.copy(statusReason = null) }
            }
        }

        val waiting = jobs.waiting()
        if (waiting.isNotEmpty() || running.isNotEmpty()) ensureService()
        if (!policy.pause) {
            val lowEnd = dev.lowMemory
            val maxSw = if (lowEnd) 1 else p.maxParallelSoftware
            for (j in waiting) {
                val hw = running.values.count { it.usesHw }
                val sw = running.size - hw
                if (running.size >= policy.maxConcurrent) break
                if (j.usesHardware && hw >= 1) continue
                if (!j.usesHardware && sw >= maxSw) continue
                // Do not run a hardware encode alongside software ones on thermally limited devices.
                if (policy.thermal && running.isNotEmpty()) break
                launchJob(j)
            }
        }
        updateWakeLock()
    }

    private fun launchJob(j: CompressionJobEntity) {
        val r = Running(j.id, j.usesHardware)
        running[j.id] = r
        r.coroutine = scope.launch(Dispatchers.IO) {
            try {
                execute(j, r, allowFallback = true)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finishCancelled(j.id, r) }
            } catch (e: Throwable) {
                Log.e(TAG, "job ${j.id} crashed", e)
                withContext(NonCancellable) {
                    fail(j.id, r, EngineError.local(ErrorCategory.INTERNAL, "Unexpected error: ${e.message}"))
                }
            } finally {
                running.remove(j.id)
                _live.update { it - j.id }
                kick()
            }
        }
    }

    private fun ensureService() {
        if (EncodingService.running) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, EncodingService::class.java))
        }.onFailure {
            // Background start restrictions (Android 12+): jobs keep waiting until the app is opened.
            Log.w(TAG, "foreground service start refused: ${it.message}")
            _policyMessage.value = context.getString(R.string.queue_open_app_to_continue)
        }
    }

    private fun updateWakeLock() {
        val active = running.isNotEmpty()
        if (active && wakeLock?.isHeld != true) {
            val pm = context.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Compressor:encoding").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L)
            }
        } else if (!active) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private suspend fun publishSnapshot(queue: List<CompressionJobEntity>?) {
        val q = queue ?: return run {
            val s = _snapshot.value
            val activeId = running.keys.firstOrNull() ?: return@run
            val l = _live.value[activeId]
            _snapshot.value = s.copy(
                activeJobId = activeId,
                progress = l?.stats?.progress ?: s.progress,
                currentFps = l?.stats?.currentFps ?: 0.0,
                averageFps = l?.stats?.averageFps ?: 0.0,
                etaUs = l?.stats?.etaUs ?: -1,
                running = running.size,
            )
        }
        val active = q.firstOrNull { running.containsKey(it.id) }
        val l = active?.let { _live.value[it.id] }
        _snapshot.value = QueueSnapshot(
            activeJobId = active?.id,
            activeName = active?.sourceName,
            progress = l?.stats?.progress ?: (active?.progress?.toDouble() ?: -1.0),
            currentFps = l?.stats?.currentFps ?: 0.0,
            averageFps = l?.stats?.averageFps ?: 0.0,
            etaUs = l?.stats?.etaUs ?: -1,
            paused = active?.status == JobStatus.PAUSED.name || active?.status == JobStatus.THERMAL_PAUSED.name,
            statusText = active?.statusReason ?: _policyMessage.value,
            running = running.size,
            waiting = q.count { it.status == JobStatus.WAITING.name },
        )
    }

    // ------------------------------------------------------------- execution

    private suspend fun execute(job: CompressionJobEntity, r: Running, allowFallback: Boolean) {
        val id = job.id
        jobs.transition(id, JobStatus.PREPARING) { it.copy(startedAt = System.currentTimeMillis(), statusReason = null) } ?: return
        val plan = ExecutionPlan.fromJson(job.planJson)
        val sourceUri = Uri.parse(job.sourceUri)

        // 1. Source must still be readable.
        val input = try {
            access.openRead(sourceUri)
        } catch (e: EngineFailure) {
            fail(id, r, e.error)
            return
        }
        input.use { inPfd ->
            // 2. Create the hidden temporary output and check free space before starting.
            val target = try {
                val name = job.outputName
                output.createPending(name, plan.container.let { mimeFor(it.format) }, job.outputFolderUri)
            } catch (e: EngineFailure) {
                fail(id, r, e.error)
                return
            }
            r.target = target
            jobs.transition(id, JobStatus.PREPARING) { it.copy(outputUri = target.uri.toString(), outputKind = target.kind) }
            val outPfd = try {
                output.openForWrite(target)
            } catch (e: EngineFailure) {
                output.discard(target)
                fail(id, r, e.error)
                return
            }
            outPfd.use { pfd ->
                val free = output.freeBytes(pfd)
                val required = job.estimatedMaxBytes + 64L * 1024 * 1024
                if (free in 0 until required) {
                    output.discard(target)
                    jobs.transition(id, JobStatus.STORAGE_ERROR) {
                        it.copy(
                            errorCode = ErrorCategory.INSUFFICIENT_STORAGE.code,
                            errorMessage = context.getString(R.string.error_not_enough_space, Format.bytes(required), Format.bytes(free)),
                            outputUri = null, completedAt = System.currentTimeMillis(),
                        )
                    }
                    return
                }

                // 3. Encode.
                jobs.transition(id, JobStatus.ENCODING)
                diagnostics.breadcrumb(
                    Breadcrumb(id, "encode", plan.video?.codec ?: "audio", plan.video?.pipeline ?: "remux", plan.video?.encoder ?: "", System.currentTimeMillis()),
                )
                var encodeStats: ProgressStats? = null
                var validation: ValidationResult? = null
                var failure: EngineError? = null
                var cancelled = false
                var lastDbWrite = 0L
                val runPlan = plan.copy(jobId = id.toString())
                engine.run(runPlan.toJson(), inPfd, pfd) { h ->
                    r.handle = h
                    if (r.throttle > 0) h.setThrottle(r.throttle)
                    if (r.userPaused || r.policyPaused) h.pause()
                }.collect { ev ->
                    when (ev.type) {
                        EngineEvents.PIPELINE_SELECTED -> {
                            val o = json.parseToJsonElement(ev.payload) as kotlinx.serialization.json.JsonObject
                            _live.update { it + (id to (it[id] ?: LiveJob(id)).copy(pipeline = o["pipeline"]?.toString()?.trim('"'), encoder = o["encoder"]?.toString()?.trim('"'))) }
                        }
                        EngineEvents.PROGRESS -> {
                            val s = json.decodeFromString(ProgressStats.serializer(), ev.payload)
                            _live.update { it + (id to (it[id] ?: LiveJob(id)).copy(stats = s)) }
                            val now = System.currentTimeMillis()
                            if (now - lastDbWrite > 2000) {
                                lastDbWrite = now
                                jobs.updateProgress(id, s.progress.toFloat().coerceAtLeast(0f), ev.payload, s.outputBytes)
                            }
                        }
                        EngineEvents.PAUSED -> Unit // status already set by the controller
                        EngineEvents.ENCODE_COMPLETE -> {
                            encodeStats = json.decodeFromString(ProgressStats.serializer(), ev.payload)
                            jobs.transition(id, JobStatus.FINALIZING) { it.copy(progress = 1f, statisticsJson = ev.payload) }
                            _live.update { it + (id to (it[id] ?: LiveJob(id)).copy(validating = true)) }
                        }
                        EngineEvents.VALIDATION_COMPLETE -> validation = json.decodeFromString(ValidationResult.serializer(), ev.payload)
                        EngineEvents.FAILED -> failure = EngineError.parse(ev.payload)
                        EngineEvents.CANCELLED -> cancelled = true
                    }
                }
                r.handle = null
                diagnostics.breadcrumb(null)

                // 4. Outcome.
                when {
                    cancelled -> {
                        output.discard(target)
                        finishCancelled(id, r)
                    }
                    failure != null -> {
                        output.discard(target)
                        val f = failure!!
                        val p = prefs.current()
                        if (allowFallback && f.kind == ErrorCategory.HARDWARE_CODEC_FAILURE && job.usesHardware &&
                            p.hardwareFallback == HardwareFallback.SOFTWARE_SAME_CODEC
                        ) {
                            val re = replanner.replan(job, EngineChoice.SOFTWARE)
                            if (re != null) {
                                val updated = jobs.transition(id, JobStatus.FAILED) { it } // reset path for retry
                                if (updated != null) {
                                    jobs.transition(id, JobStatus.WAITING) {
                                        it.copy(
                                            planJson = re.first.toJson(), summaryJson = AppJson.json.encodeToString(JobSummary.serializer(), re.second),
                                            usesHardware = false, statusReason = "Hardware encoder failed (${f.message}); retrying with software, same codec.",
                                            outputUri = null,
                                        )
                                    }
                                    return
                                }
                            }
                        }
                        fail(id, r, f)
                    }
                    encodeStats != null && (validation?.ok == true) -> finalizeSuccess(job, target, encodeStats!!)
                    else -> {
                        output.discard(target)
                        fail(id, r, EngineError.local(ErrorCategory.OUTPUT_VALIDATION_FAILURE,
                            context.getString(R.string.error_validation_failed)))
                    }
                }
            }
        }
    }

    private suspend fun finalizeSuccess(job: CompressionJobEntity, target: OutputTarget, stats: ProgressStats) {
        val id = job.id
        val finalUri = try {
            output.finalizeOutput(target, replaceExisting = job.replaceExisting)
        } catch (e: EngineFailure) {
            output.discard(target)
            fail(id, null, e.error)
            return
        }
        val size = output.size(finalUri).takeIf { it > 0 } ?: stats.outputBytes
        val summary = runCatching { AppJson.json.decodeFromString(JobSummary.serializer(), job.summaryJson) }.getOrDefault(JobSummary())
        val now = System.currentTimeMillis()
        var originalDeleted = false
        var reason: String? = null
        if (job.replaceOriginal) {
            val src = Uri.parse(job.sourceUri)
            if (access.canDelete(src) && access.delete(src)) originalDeleted = true
            else reason = context.getString(R.string.replace_original_not_supported)
        }
        jobs.transition(id, JobStatus.COMPLETE) {
            it.copy(outputUri = finalUri.toString(), completedAt = now, progress = 1f, outputBytes = size, statusReason = reason)
        }
        history.add(
            CompressionHistoryEntity(
                jobId = id, sourceName = job.sourceName, sourceUri = job.sourceUri, sourceSize = job.sourceSize,
                outputName = target.finalName, outputUri = finalUri.toString(), outputSize = size,
                sourceCodec = summary.sourceCodec, outputCodec = summary.outputCodec,
                sourceResolution = summary.sourceResolution, outputResolution = summary.outputResolution,
                sourceFps = summary.sourceFps, outputFps = summary.outputFps,
                sourceAudio = summary.sourceAudio, outputAudio = summary.outputAudio,
                durationUs = summary.durationUs, encodeTimeMs = (if (stats.elapsedUs > 0) stats.elapsedUs else stats.wallTimeUs) / 1000, pipeline = stats.pipeline.ifEmpty { summary.pipeline },
                encoder = stats.encoder, configJson = job.configJson, completedAt = now, originalDeleted = originalDeleted,
            ),
        )
        val saved = job.sourceSize - size
        notifications.result(
            id, context.getString(R.string.notif_done_title),
            context.getString(R.string.notif_done_text, job.sourceName, Format.bytes(job.sourceSize), Format.bytes(size),
                if (job.sourceSize > 0) Format.percent(saved.toDouble() / job.sourceSize) else "—"),
        )
    }

    private suspend fun fail(id: Long, r: Running?, e: EngineError) {
        r?.target?.let { output.discard(it) }
        val status = when (e.kind) {
            ErrorCategory.INSUFFICIENT_STORAGE -> JobStatus.STORAGE_ERROR
            ErrorCategory.UNSUPPORTED_CODEC -> JobStatus.FAILED
            else -> JobStatus.FAILED
        }
        jobs.transition(id, status) {
            it.copy(
                errorCode = e.category, errorMessage = e.message, errorJson = AppJson.json.encodeToString(EngineError.serializer(), e),
                outputUri = null, completedAt = System.currentTimeMillis(),
            )
        }
        val j = jobs.get(id)
        notifications.result(id, context.getString(R.string.notif_failed_title), "${j?.sourceName ?: ""}: ${e.message}")
    }

    private suspend fun finishCancelled(id: Long, r: Running) {
        r.target?.let { output.discard(it) }
        jobs.transition(id, JobStatus.CANCELLED) { it.copy(outputUri = null, completedAt = System.currentTimeMillis(), statusReason = null) }
    }

    /** Called by the service when Android's foreground time limit is reached (API 35+). */
    suspend fun onForegroundTimeout() {
        running.values.forEach { r ->
            r.handle?.cancel()
            jobs.transition(r.id, JobStatus.INTERRUPTED) { it.copy(statusReason = context.getString(R.string.queue_time_limit)) }
        }
    }

    companion object {
        private const val TAG = "QueueManager"
        fun mimeFor(muxer: String): String = when (muxer) {
            "mp4", "ipod" -> "video/mp4"
            "matroska" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mpegts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }
}
