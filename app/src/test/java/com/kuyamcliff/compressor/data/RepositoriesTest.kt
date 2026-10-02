package com.kuyamcliff.compressor.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuyamcliff.compressor.data.db.AppDatabase
import com.kuyamcliff.compressor.data.db.CompressionJobEntity
import com.kuyamcliff.compressor.data.repo.JobRepository
import com.kuyamcliff.compressor.data.repo.PresetRepository
import com.kuyamcliff.compressor.domain.BuiltInPresets
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.JobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class RepositoriesTest {
    private lateinit var db: AppDatabase
    private lateinit var jobs: JobRepository
    private lateinit var presets: PresetRepository

    @Before fun setUp() {
        db = AppDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        jobs = JobRepository(db)
        presets = PresetRepository(db)
    }

    @After fun tearDown() = db.close()

    private fun job(name: String) = CompressionJobEntity(
        sourceUri = "content://x/$name", sourceName = name, sourceSize = 1000, outputName = "$name.mp4",
        status = JobStatus.WAITING.name, createdAt = 0, configJson = "{}", planJson = "{}",
    )

    @Test fun enqueueAssignsIncreasingPositions() = runTest {
        val a = jobs.enqueue(job("a"))
        val b = jobs.enqueue(job("b"))
        assertTrue(jobs.get(b)!!.position > jobs.get(a)!!.position)
        assertEquals(listOf(a, b), jobs.waiting().map { it.id })
    }

    @Test fun transitionsAreValidated() = runTest {
        val id = jobs.enqueue(job("a"))
        assertNull("WAITING -> COMPLETE must be rejected", jobs.transition(id, JobStatus.COMPLETE))
        assertNotNull(jobs.transition(id, JobStatus.PREPARING))
        assertNotNull(jobs.transition(id, JobStatus.ENCODING))
        assertNotNull(jobs.transition(id, JobStatus.FINALIZING))
        assertNotNull(jobs.transition(id, JobStatus.COMPLETE))
        assertNull("late PAUSED after COMPLETE must be ignored", jobs.transition(id, JobStatus.PAUSED))
        assertEquals(JobStatus.COMPLETE.name, jobs.get(id)!!.status)
    }

    @Test fun moveSwapsOrder() = runTest {
        val a = jobs.enqueue(job("a"))
        val b = jobs.enqueue(job("b"))
        jobs.move(b, -1)
        assertEquals(listOf(b, a), jobs.waiting().map { it.id })
    }

    @Test fun recoveryInterruptsRunningJobs() = runTest {
        val id = jobs.enqueue(job("a"))
        jobs.transition(id, JobStatus.PREPARING)
        jobs.transition(id, JobStatus.ENCODING)
        val recovered = jobs.recoverAfterRestart()
        assertEquals(1, recovered.size)
        assertEquals(JobStatus.INTERRUPTED.name, jobs.get(id)!!.status)
        assertNotNull(jobs.transition(id, JobStatus.WAITING))
    }

    @Test fun presetsSeedAndCustomCrud() = runTest {
        presets.seedBuiltIns()
        assertEquals(BuiltInPresets.all.size, presets.presets.first().size)
        val p = presets.saveCustom("Mine", "desc", CompressionConfig())
        presets.rename(p.id, "Renamed")
        assertEquals("Renamed", presets.get(p.id)!!.name)
        presets.rename(BuiltInPresets.default.id, "Hacked")
        assertEquals(BuiltInPresets.default.name, presets.get(BuiltInPresets.default.id)!!.name)
        val exported = presets.exportCustom()
        presets.delete(p.id)
        assertNull(presets.get(p.id))
        assertEquals(1, presets.import(exported))
        presets.delete(BuiltInPresets.default.id)
        assertNotNull("built-ins cannot be deleted", presets.get(BuiltInPresets.default.id))
    }
}
