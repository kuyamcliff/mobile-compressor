package com.kuyamcliff.compressor.model

import com.kuyamcliff.compressor.data.prefs.PerformanceProfile
import com.kuyamcliff.compressor.queue.DeviceMonitor
import com.kuyamcliff.compressor.queue.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStateMachineTest {
    @Test fun normalLifecycle() {
        val path = listOf(JobStatus.WAITING, JobStatus.PREPARING, JobStatus.ENCODING, JobStatus.FINALIZING, JobStatus.COMPLETE)
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a->$b", JobStateMachine.canTransition(a, b)) }
    }

    @Test fun completeIsFinal() {
        JobStatus.entries.filter { it != JobStatus.COMPLETE }.forEach { assertFalse(JobStateMachine.canTransition(JobStatus.COMPLETE, it)) }
    }

    @Test fun latePauseAfterCompletionRejected() = assertFalse(JobStateMachine.canTransition(JobStatus.COMPLETE, JobStatus.PAUSED))

    @Test fun cannotSkipToComplete() {
        assertFalse(JobStateMachine.canTransition(JobStatus.WAITING, JobStatus.COMPLETE))
        assertFalse(JobStateMachine.canTransition(JobStatus.ENCODING, JobStatus.COMPLETE))
    }

    @Test fun retryFromFailures() {
        listOf(JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.STORAGE_ERROR, JobStatus.INTERRUPTED).forEach {
            assertTrue(JobStateMachine.canTransition(it, JobStatus.WAITING))
            assertTrue(it.canRetry)
        }
    }

    @Test fun recoveryMarksRunningInterrupted() {
        assertEquals(JobStatus.INTERRUPTED, JobStateMachine.recoveredStatus(JobStatus.ENCODING))
        assertEquals(JobStatus.INTERRUPTED, JobStateMachine.recoveredStatus(JobStatus.PAUSED))
        assertEquals(JobStatus.WAITING, JobStateMachine.recoveredStatus(JobStatus.WAITING))
        assertEquals(JobStatus.COMPLETE, JobStateMachine.recoveredStatus(JobStatus.COMPLETE))
    }

    @Test fun everyStatusHasTransitionsDefined() {
        JobStatus.entries.forEach { assertTrue(JobStateMachine.canTransition(it, it)) }
    }
}

class DevicePolicyTest {
    private val cool = DeviceState(thermalStatus = 0, charging = true, batteryPercent = 80)

    @Test fun coolDeviceRunsFreely() {
        PerformanceProfile.entries.forEach {
            val p = DeviceMonitor.policy(it, cool, chargingOnly = false)
            assertFalse(p.pause)
            assertEquals(0, p.throttlePercent)
        }
    }

    @Test fun chargingOnlyPausesOnBattery() {
        val p = DeviceMonitor.policy(PerformanceProfile.BALANCED, cool.copy(charging = false), chargingOnly = true)
        assertTrue(p.pause)
    }

    @Test fun lowBatteryPauses() {
        assertTrue(DeviceMonitor.policy(PerformanceProfile.PERFORMANCE, cool.copy(charging = false, batteryPercent = 5), false).pause)
    }

    @Test fun criticalThermalAlwaysPauses() {
        PerformanceProfile.entries.forEach {
            assertTrue(it.name, DeviceMonitor.policy(it, cool.copy(thermalStatus = 4), false).pause)
        }
    }

    @Test fun thermalProfileIsMostConservative() {
        val moderate = cool.copy(thermalStatus = 2)
        val t = DeviceMonitor.policy(PerformanceProfile.THERMAL, moderate, false)
        val p = DeviceMonitor.policy(PerformanceProfile.PERFORMANCE, moderate, false)
        assertTrue(t.throttlePercent > p.throttlePercent)
        assertTrue(t.reason != null)
    }
}
