package com.kuyamcliff.compressor.model

/**
 * Queue job status (persisted in Room as the enum name).
 *
 * Transitions are validated by [JobStateMachine]; anything not listed is
 * rejected, so out-of-order events (e.g. a late PAUSED after COMPLETE) cannot
 * corrupt persisted state.
 */
enum class JobStatus {
    WAITING,
    PREPARING,
    ANALYZING,
    ENCODING,
    PAUSED,
    THERMAL_PAUSED,
    FINALIZING,
    COMPLETE,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    STORAGE_ERROR,
    UNSUPPORTED;

    val isTerminal: Boolean
        get() = this == COMPLETE || this == FAILED || this == CANCELLED || this == STORAGE_ERROR || this == UNSUPPORTED

    val isActive: Boolean
        get() = this == PREPARING || this == ANALYZING || this == ENCODING || this == FINALIZING

    val isPausedLike: Boolean get() = this == PAUSED || this == THERMAL_PAUSED

    /** Jobs that hold native resources right now. */
    val isRunning: Boolean get() = isActive || isPausedLike

    val canRetry: Boolean get() = this == FAILED || this == CANCELLED || this == INTERRUPTED || this == STORAGE_ERROR
}

object JobStateMachine {
    private val allowed: Map<JobStatus, Set<JobStatus>> = mapOf(
        JobStatus.WAITING to setOf(JobStatus.PREPARING, JobStatus.CANCELLED, JobStatus.PAUSED, JobStatus.UNSUPPORTED),
        JobStatus.PREPARING to setOf(
            JobStatus.ANALYZING, JobStatus.ENCODING, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.STORAGE_ERROR,
            JobStatus.UNSUPPORTED, JobStatus.INTERRUPTED, JobStatus.WAITING,
        ),
        JobStatus.ANALYZING to setOf(JobStatus.ENCODING, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.INTERRUPTED),
        JobStatus.ENCODING to setOf(
            JobStatus.PAUSED, JobStatus.THERMAL_PAUSED, JobStatus.FINALIZING, JobStatus.FAILED, JobStatus.CANCELLED,
            JobStatus.INTERRUPTED, JobStatus.STORAGE_ERROR,
        ),
        JobStatus.PAUSED to setOf(JobStatus.ENCODING, JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.INTERRUPTED, JobStatus.WAITING, JobStatus.FINALIZING),
        JobStatus.THERMAL_PAUSED to setOf(JobStatus.ENCODING, JobStatus.PAUSED, JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.INTERRUPTED, JobStatus.FINALIZING),
        JobStatus.FINALIZING to setOf(JobStatus.COMPLETE, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.INTERRUPTED, JobStatus.STORAGE_ERROR),
        JobStatus.INTERRUPTED to setOf(JobStatus.WAITING, JobStatus.CANCELLED),
        JobStatus.FAILED to setOf(JobStatus.WAITING),
        JobStatus.CANCELLED to setOf(JobStatus.WAITING),
        JobStatus.STORAGE_ERROR to setOf(JobStatus.WAITING),
        JobStatus.COMPLETE to emptySet(),
        JobStatus.UNSUPPORTED to emptySet(),
    )

    fun canTransition(from: JobStatus, to: JobStatus): Boolean = from == to || allowed[from]?.contains(to) == true

    /** On process start, any job that was mid-flight could not have survived. */
    fun recoveredStatus(persisted: JobStatus): JobStatus =
        if (persisted.isRunning) JobStatus.INTERRUPTED else persisted
}

enum class JobPriority(val weight: Int) { LOW(0), NORMAL(1), HIGH(2) }
