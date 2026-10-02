package com.kuyamcliff.compressor.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Query("SELECT * FROM compression_job WHERE status NOT IN ('COMPLETE') ORDER BY priority DESC, position ASC, id ASC")
    fun observeQueue(): Flow<List<CompressionJobEntity>>

    @Query("SELECT * FROM compression_job ORDER BY priority DESC, position ASC, id ASC")
    fun observeAll(): Flow<List<CompressionJobEntity>>

    @Query("SELECT * FROM compression_job WHERE id = :id")
    suspend fun get(id: Long): CompressionJobEntity?

    @Query("SELECT * FROM compression_job WHERE id = :id")
    fun observe(id: Long): Flow<CompressionJobEntity?>

    @Query("SELECT * FROM compression_job WHERE status = 'WAITING' ORDER BY priority DESC, position ASC, id ASC")
    suspend fun waiting(): List<CompressionJobEntity>

    @Query("SELECT * FROM compression_job WHERE status IN ('PREPARING','ANALYZING','ENCODING','PAUSED','THERMAL_PAUSED','FINALIZING')")
    suspend fun inFlight(): List<CompressionJobEntity>

    @Query("SELECT COALESCE(MAX(position), 0) FROM compression_job")
    suspend fun maxPosition(): Long

    @Insert
    suspend fun insert(job: CompressionJobEntity): Long

    @Update
    suspend fun update(job: CompressionJobEntity)

    @Query("UPDATE compression_job SET progress = :progress, statisticsJson = :stats, outputBytes = :bytes WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float, stats: String, bytes: Long)

    @Query("DELETE FROM compression_job WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM compression_job WHERE status = 'COMPLETE'")
    suspend fun deleteCompleted()
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM compression_preset ORDER BY builtIn DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CompressionPresetEntity>>

    @Query("SELECT * FROM compression_preset WHERE id = :id")
    suspend fun get(id: String): CompressionPresetEntity?

    @Upsert
    suspend fun upsert(p: CompressionPresetEntity)

    @Query("DELETE FROM compression_preset WHERE id = :id AND builtIn = 0")
    suspend fun deleteCustom(id: String)

    @Query("SELECT * FROM compression_preset WHERE builtIn = 0")
    suspend fun custom(): List<CompressionPresetEntity>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM compression_history ORDER BY completedAt DESC")
    fun observeAll(): Flow<List<CompressionHistoryEntity>>

    @Query("SELECT * FROM compression_history WHERE id = :id")
    suspend fun get(id: Long): CompressionHistoryEntity?

    @Query("SELECT * FROM compression_history WHERE jobId = :jobId ORDER BY id DESC LIMIT 1")
    suspend fun forJob(jobId: Long): CompressionHistoryEntity?

    @Insert
    suspend fun insert(h: CompressionHistoryEntity): Long

    @Update
    suspend fun update(h: CompressionHistoryEntity)

    @Query("DELETE FROM compression_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM compression_history WHERE encodeTimeMs > 0 ORDER BY completedAt DESC LIMIT 50")
    suspend fun recentForSpeed(): List<CompressionHistoryEntity>
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM app_setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_setting WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: AppSettingEntity)

    @Query("DELETE FROM app_setting WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM app_setting")
    suspend fun clear()
}

@Dao
interface SourceCacheDao {
    @Query("SELECT * FROM source_metadata_cache WHERE uri = :uri")
    suspend fun get(uri: String): SourceMetadataCacheEntity?

    @Upsert
    suspend fun upsert(e: SourceMetadataCacheEntity)

    @Query("SELECT * FROM source_metadata_cache ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SourceMetadataCacheEntity>>

    @Query("UPDATE source_metadata_cache SET complexityJson = :json WHERE uri = :uri")
    suspend fun setComplexity(uri: String, json: String)

    @Query("DELETE FROM source_metadata_cache WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM source_metadata_cache WHERE lastOpenedAt < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [
        CompressionJobEntity::class, CompressionPresetEntity::class, CompressionHistoryEntity::class,
        AppSettingEntity::class, SourceMetadataCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun presets(): PresetDao
    abstract fun history(): HistoryDao
    abstract fun settings(): SettingDao
    abstract fun sources(): SourceCacheDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): AppDatabase {
            val builder = if (inMemory) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            else Room.databaseBuilder(context, AppDatabase::class.java, "compressor.db")
            return builder.build()
        }
    }
}
