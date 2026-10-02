package com.kuyamcliff.compressor.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted queue job. Large data (plans, statistics) are JSON text, never
 * binary blobs. State changes are written transactionally by JobRepository.
 */
@Entity(tableName = "compression_job", indices = [Index("status"), Index("position")])
data class CompressionJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val sourceName: String,
    val sourceSize: Long,
    val sourceDurationUs: Long = 0,
    val outputName: String,
    /** Final location (MediaStore item or SAF document); null until created. */
    val outputUri: String? = null,
    /** "mediastore" or "saf". */
    val outputKind: String = "mediastore",
    val outputFolderUri: String? = null,
    val status: String,
    val statusReason: String? = null,
    val priority: Int = 1,
    val position: Long = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val progress: Float = 0f,
    val configJson: String,
    val planJson: String,
    val summaryJson: String = "{}",
    val statisticsJson: String? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val errorJson: String? = null,
    val usesHardware: Boolean = false,
    val pipeline: String = "",
    val estimatedMaxBytes: Long = 0,
    val outputBytes: Long = 0,
    val replaceOriginal: Boolean = false,
    val presetName: String? = null,
)

@Entity(tableName = "compression_preset")
data class CompressionPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val configJson: String,
    val requirementsJson: String = "{}",
    val builtIn: Boolean = false,
    val allowsFpsReduction: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "compression_history", indices = [Index("completedAt")])
data class CompressionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val sourceName: String,
    val sourceUri: String,
    val sourceSize: Long,
    val outputName: String,
    val outputUri: String,
    val outputSize: Long,
    val sourceCodec: String,
    val outputCodec: String,
    val sourceResolution: String,
    val outputResolution: String,
    val sourceFps: Double,
    val outputFps: Double,
    val sourceAudio: String,
    val outputAudio: String,
    val durationUs: Long,
    val encodeTimeMs: Long,
    val pipeline: String,
    val encoder: String,
    val configJson: String,
    val completedAt: Long,
    val psnr: Double? = null,
    val ssim: Double? = null,
    val outputDeleted: Boolean = false,
    val originalDeleted: Boolean = false,
)

@Entity(tableName = "app_setting")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "source_metadata_cache", indices = [Index("lastOpenedAt")])
data class SourceMetadataCacheEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val infoJson: String,
    @ColumnInfo(defaultValue = "NULL") val complexityJson: String? = null,
    val analyzedAt: Long,
    val lastOpenedAt: Long,
)
