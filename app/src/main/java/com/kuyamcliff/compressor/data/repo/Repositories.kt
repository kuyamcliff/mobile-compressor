package com.kuyamcliff.compressor.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.kuyamcliff.compressor.data.db.AppDatabase
import com.kuyamcliff.compressor.data.db.AppSettingEntity
import com.kuyamcliff.compressor.data.db.CompressionHistoryEntity
import com.kuyamcliff.compressor.data.db.CompressionJobEntity
import com.kuyamcliff.compressor.data.db.CompressionPresetEntity
import com.kuyamcliff.compressor.data.db.SourceMetadataCacheEntity
import com.kuyamcliff.compressor.data.storage.SourceAccess
import com.kuyamcliff.compressor.domain.BuiltInPresets
import com.kuyamcliff.compressor.domain.Preset
import com.kuyamcliff.compressor.domain.PresetCategory
import com.kuyamcliff.compressor.domain.PresetRequirements
import com.kuyamcliff.compressor.engine.ComplexityReport
import com.kuyamcliff.compressor.engine.MediaEngine
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.JobPriority
import com.kuyamcliff.compressor.model.JobStateMachine
import com.kuyamcliff.compressor.model.JobStatus
import com.kuyamcliff.compressor.model.SourceFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Shared JSON configuration for persisted models. */
object AppJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; prettyPrint = false }
    val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    fun encodeConfig(c: CompressionConfig): String = json.encodeToString(CompressionConfig.serializer(), c)
    fun decodeConfig(s: String): CompressionConfig = json.decodeFromString(CompressionConfig.serializer(), s)
}

// ---------------------------------------------------------------------------

class PresetRepository(private val db: AppDatabase) {
    private val dao = db.presets()

    /** Built-ins are re-seeded on every start so app updates refresh them. */
    suspend fun seedBuiltIns() {
        val now = System.currentTimeMillis()
        db.withTransaction {
            BuiltInPresets.all.forEach { p ->
                dao.upsert(toEntity(p, createdAt = now))
            }
        }
    }

    val presets: Flow<List<Preset>> = dao.observeAll().map { list -> list.mapNotNull { fromEntity(it) } }

    suspend fun get(id: String): Preset? = dao.get(id)?.let { fromEntity(it) }

    suspend fun saveCustom(name: String, description: String, config: CompressionConfig, id: String? = null): Preset {
        val p = Preset(
            id = id ?: "custom_" + UUID.randomUUID().toString().take(8),
            name = name.trim().take(60).ifEmpty { "Custom preset" },
            description = description.take(200),
            category = PresetCategory.CUSTOM,
            config = config,
            builtIn = false,
        )
        dao.upsert(toEntity(p, System.currentTimeMillis()))
        return p
    }

    suspend fun rename(id: String, name: String) {
        val e = dao.get(id) ?: return
        if (e.builtIn) return
        dao.upsert(e.copy(name = name.trim().take(60), updatedAt = System.currentTimeMillis()))
    }

    suspend fun duplicate(id: String): Preset? {
        val p = get(id) ?: return null
        return saveCustom("${p.name} copy", p.description, p.config)
    }

    suspend fun delete(id: String) = dao.deleteCustom(id)

    /** Portable JSON (PRD §115, §154): presets only, no file paths. */
    suspend fun exportCustom(): String {
        val list = dao.custom().mapNotNull { fromEntity(it) }.map { it.copy(config = it.config.withoutPrivatePaths()) }
        return AppJson.pretty.encodeToString(PresetExport.serializer(), PresetExport(presets = list))
    }

    fun exportOne(p: Preset): String =
        AppJson.pretty.encodeToString(PresetExport.serializer(), PresetExport(presets = listOf(p.copy(builtIn = false, config = p.config.withoutPrivatePaths()))))

    /** Returns the number of imported presets; invalid entries are skipped. */
    suspend fun import(text: String): Int {
        val export = AppJson.json.decodeFromString(PresetExport.serializer(), text)
        var n = 0
        for (p in export.presets.take(500)) {
            saveCustom(p.name, p.description, p.config)
            n++
        }
        return n
    }

    private fun toEntity(p: Preset, createdAt: Long) = CompressionPresetEntity(
        id = p.id, name = p.name, description = p.description, category = p.category.name,
        configJson = AppJson.encodeConfig(p.config),
        requirementsJson = AppJson.json.encodeToString(PresetRequirements.serializer(), p.requirements),
        builtIn = p.builtIn, allowsFpsReduction = p.allowsFpsReduction, createdAt = createdAt, updatedAt = createdAt,
    )

    private fun fromEntity(e: CompressionPresetEntity): Preset? = runCatching {
        Preset(
            id = e.id, name = e.name, description = e.description,
            category = runCatching { PresetCategory.valueOf(e.category) }.getOrDefault(PresetCategory.CUSTOM),
            config = AppJson.decodeConfig(e.configJson), builtIn = e.builtIn,
            requirements = AppJson.json.decodeFromString(PresetRequirements.serializer(), e.requirementsJson),
            allowsFpsReduction = e.allowsFpsReduction,
        )
    }.getOrNull()
}

@Serializable
data class PresetExport(val format: String = "compressor-presets", val version: Int = 1, val presets: List<Preset>)

/** Removes anything that could reveal private file locations (PRD §116, §154). */
fun CompressionConfig.withoutPrivatePaths(): CompressionConfig = copy(
    output = output.copy(folderUri = null),
    subtitles = subtitles.copy(external = emptyList()),
)

// ---------------------------------------------------------------------------

class JobRepository(private val db: AppDatabase) {
    private val dao = db.jobs()

    val queue: Flow<List<CompressionJobEntity>> = dao.observeQueue()
    val all: Flow<List<CompressionJobEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<CompressionJobEntity?> = dao.observe(id)
    suspend fun get(id: Long) = dao.get(id)

    suspend fun enqueue(job: CompressionJobEntity): Long = db.withTransaction {
        val pos = dao.maxPosition() + 1
        dao.insert(job.copy(position = pos, status = JobStatus.WAITING.name, createdAt = System.currentTimeMillis()))
    }

    /**
     * Applies a status change only if the state machine allows it. Returns the
     * updated row, or null when the transition was rejected (stale/duplicate event).
     */
    suspend fun transition(id: Long, to: JobStatus, mutate: (CompressionJobEntity) -> CompressionJobEntity = { it }): CompressionJobEntity? =
        db.withTransaction {
            val cur = dao.get(id) ?: return@withTransaction null
            val from = runCatching { JobStatus.valueOf(cur.status) }.getOrDefault(JobStatus.FAILED)
            if (!JobStateMachine.canTransition(from, to)) return@withTransaction null
            val next = mutate(cur).copy(status = to.name)
            dao.update(next)
            next
        }

    suspend fun update(job: CompressionJobEntity) = dao.update(job)
    suspend fun updateProgress(id: Long, progress: Float, stats: String, bytes: Long) = dao.updateProgress(id, progress, stats, bytes)
    suspend fun waiting() = dao.waiting()
    suspend fun inFlight() = dao.inFlight()
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clearCompleted() = dao.deleteCompleted()

    suspend fun setPriority(id: Long, p: JobPriority) {
        val j = dao.get(id) ?: return
        dao.update(j.copy(priority = p.weight))
    }

    /** Swaps queue positions with the neighbour in the given direction (-1 up, +1 down). */
    suspend fun move(id: Long, direction: Int) = db.withTransaction {
        val list = dao.waiting()
        val i = list.indexOfFirst { it.id == id }
        val j = i + direction
        if (i < 0 || j !in list.indices) return@withTransaction
        val a = list[i]
        val b = list[j]
        val pa = if (a.position == b.position) a.position + direction else a.position
        dao.update(a.copy(position = b.position, priority = b.priority))
        dao.update(b.copy(position = pa, priority = a.priority))
    }

    /** Process start: anything that was mid-flight is now INTERRUPTED (PRD §51, §130). */
    suspend fun recoverAfterRestart(): List<CompressionJobEntity> = db.withTransaction {
        dao.inFlight().map { j ->
            val recovered = j.copy(status = JobStateMachine.recoveredStatus(JobStatus.valueOf(j.status)).name,
                statusReason = "The app was closed or the device restarted during this job.")
            dao.update(recovered)
            recovered
        }
    }
}

// ---------------------------------------------------------------------------

class HistoryRepository(private val db: AppDatabase) {
    private val dao = db.history()
    val history: Flow<List<CompressionHistoryEntity>> = dao.observeAll()
    suspend fun add(h: CompressionHistoryEntity) = dao.insert(h)
    suspend fun get(id: Long) = dao.get(id)
    suspend fun forJob(jobId: Long) = dao.forJob(jobId)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun markOutputDeleted(id: Long) { dao.get(id)?.let { dao.update(it.copy(outputDeleted = true)) } }

    /** Measured throughput (output pixels per second) per "PIPELINE:CODEC" from past jobs on this device. */
    suspend fun measuredThroughput(): Map<String, Double> {
        val rows = dao.recentForSpeed()
        return rows.groupBy { "${it.pipeline}:${it.outputCodec.uppercase()}" }.mapValues { (_, list) ->
            list.mapNotNull { h ->
                val (w, hh) = h.outputResolution.split('×', 'x').mapNotNull { it.trim().toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else return@mapNotNull null }
                val frames = h.durationUs / 1e6 * h.outputFps
                if (h.encodeTimeMs <= 0) null else w.toDouble() * hh * frames / (h.encodeTimeMs / 1000.0)
            }.let { if (it.isEmpty()) 0.0 else it.sorted()[it.size / 2] }
        }.filterValues { it > 0 }
    }
}

// ---------------------------------------------------------------------------

class SettingsRepository(private val db: AppDatabase) {
    private val dao = db.settings()

    suspend fun lastConfig(): CompressionConfig? = dao.get(KEY_LAST_CONFIG)?.let { runCatching { AppJson.decodeConfig(it) }.getOrNull() }
    suspend fun saveLastConfig(c: CompressionConfig) = dao.put(AppSettingEntity(KEY_LAST_CONFIG, AppJson.encodeConfig(c.withoutPrivatePaths())))
    suspend fun get(key: String) = dao.get(key)
    suspend fun put(key: String, value: String) = dao.put(AppSettingEntity(key, value))
    suspend fun clear() = dao.clear()

    /** Folders the user asked to watch (manual refresh, no background polling). */
    suspend fun watchedFolders(): List<String> =
        dao.get(KEY_WATCHED)?.let { runCatching { AppJson.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()

    suspend fun setWatchedFolders(list: List<String>) =
        dao.put(AppSettingEntity(KEY_WATCHED, AppJson.json.encodeToString(ListSerializer(String.serializer()), list.distinct())))

    suspend fun seenInFolder(folder: String): Set<String> =
        dao.get("seen:$folder")?.let { runCatching { AppJson.json.decodeFromString(ListSerializer(String.serializer()), it).toSet() }.getOrNull() } ?: emptySet()

    suspend fun setSeenInFolder(folder: String, uris: Set<String>) =
        dao.put(AppSettingEntity("seen:$folder", AppJson.json.encodeToString(ListSerializer(String.serializer()), uris.toList())))

    companion object {
        const val KEY_LAST_CONFIG = "last_config"
        const val KEY_WATCHED = "watched_folders"
    }
}


// ---------------------------------------------------------------------------

/** Source analysis with a metadata cache keyed by URI + size + modification time. */
class SourceRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val access: SourceAccess,
    private val engine: MediaEngine,
) {
    private val dao = db.sources()
    val recent: Flow<List<SourceMetadataCacheEntity>> = dao.observeRecent(20)

    suspend fun analyze(uri: Uri): SourceFile {
        val meta = access.meta(uri)
        val cached = dao.get(uri.toString())
        val info = if (cached != null && cached.size == meta.size && cached.lastModified == meta.lastModified) {
            runCatching { engine.parseSourceInfo(cached.infoJson) }.getOrNull()
        } else null
        val resolved = info ?: access.openRead(uri).use { pfd -> engine.probe(pfd, meta.displayName) }
        val now = System.currentTimeMillis()
        dao.upsert(
            SourceMetadataCacheEntity(
                uri = uri.toString(), displayName = meta.displayName, size = meta.size, lastModified = meta.lastModified,
                infoJson = engine.encodeSourceInfo(resolved), complexityJson = cached?.complexityJson.takeIf { info != null },
                analyzedAt = if (info != null) cached!!.analyzedAt else now, lastOpenedAt = now,
            ),
        )
        return SourceFile(uri.toString(), meta.displayName, meta.size, meta.location, meta.mimeType.orEmpty(), resolved)
    }

    suspend fun complexity(uri: Uri): ComplexityReport {
        dao.get(uri.toString())?.complexityJson?.let { j ->
            runCatching { return AppJson.json.decodeFromString(ComplexityReport.serializer(), j) }
        }
        val report = access.openRead(uri).use { engine.analyzeComplexity(it) }
        dao.setComplexity(uri.toString(), AppJson.json.encodeToString(ComplexityReport.serializer(), report))
        return report
    }

    suspend fun cachedComplexity(uri: String): ComplexityReport? =
        dao.get(uri)?.complexityJson?.let { runCatching { AppJson.json.decodeFromString(ComplexityReport.serializer(), it) }.getOrNull() }

    suspend fun forget(uri: String) = dao.delete(uri)
}
