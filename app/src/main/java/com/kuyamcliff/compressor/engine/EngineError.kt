package com.kuyamcliff.compressor.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** Mirrors vc::ErrorCategory (native/engine/src/core/Errors.h). Codes are part of the JNI contract. */
enum class ErrorCategory(val code: Int) {
    NONE(0),
    INVALID_INPUT(1),
    UNSUPPORTED_CODEC(2),
    UNSUPPORTED_PROFILE(3),
    UNSUPPORTED_PIXEL_FORMAT(4),
    HARDWARE_CODEC_FAILURE(5),
    FFMPEG_FAILURE(6),
    OUTPUT_PATH_FAILURE(7),
    INSUFFICIENT_STORAGE(8),
    PERMISSION_DENIED(9),
    CONTENT_URI_EXPIRED(10),
    SOURCE_DISAPPEARED(11),
    THERMAL_RESTRICTION(12),
    PROCESS_INTERRUPTION(13),
    MEMORY_FAILURE(14),
    OUTPUT_VALIDATION_FAILURE(15),
    INVALID_CONFIGURATION(16),
    INVALID_ENCODER_OPTION(17),
    CANCELLED(18),
    INTERNAL(19);

    companion object {
        fun fromCode(code: Int): ErrorCategory = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

@Serializable
data class EngineError(
    val category: Int = ErrorCategory.INTERNAL.code,
    val categoryName: String = "",
    val code: Int = 0,
    val message: String = "",
    val detail: String = "",
    val stage: String = "",
    val context: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
) {
    val kind: ErrorCategory get() = ErrorCategory.fromCode(category)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): EngineError = try {
            json.decodeFromString(serializer(), text)
        } catch (e: Exception) {
            EngineError(message = text.take(500))
        }

        /** Engine results are either a payload or {"error": {...}}. */
        fun fromResult(obj: JsonObject): EngineError? =
            obj["error"]?.let { json.decodeFromJsonElement<EngineError>(it.jsonObject) }

        fun local(kind: ErrorCategory, message: String, suggestions: List<String> = emptyList()) =
            EngineError(category = kind.code, categoryName = kind.name.lowercase(), message = message, suggestions = suggestions)
    }
}

class EngineFailure(val error: EngineError) : Exception(error.message)
