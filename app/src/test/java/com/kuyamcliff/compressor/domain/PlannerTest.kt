package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.Fixtures
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.EngineChoice
import com.kuyamcliff.compressor.model.ExecutionPlan
import com.kuyamcliff.compressor.model.FilterStrength
import com.kuyamcliff.compressor.model.FpsChoice
import com.kuyamcliff.compressor.model.FpsMode
import com.kuyamcliff.compressor.model.HdrMode
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.ResolutionChoice
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.model.VideoCodec
import com.kuyamcliff.compressor.model.VideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlannerTest {
    private val planner = Planner()
    private fun cfg(f: (CompressionConfig) -> CompressionConfig = { it }) = f(CompressionConfig())
    private fun CompressionConfig.video(f: (com.kuyamcliff.compressor.model.VideoSettings) -> com.kuyamcliff.compressor.model.VideoSettings) = copy(video = f(video))

    @Test fun softwareHevcWhenNoHardware() {
        val r = planner.plan(cfg(), Fixtures.ctx())
        assertEquals(PipelineKind.SOFTWARE, r.summary.pipeline)
        val v = r.plan.video!!
        assertEquals("libkvazaar", v.encoder)
        assertEquals(0, v.width % 8)
        assertEquals(0, v.height % 8)
        assertEquals("cqp", v.rateControl!!.mode)
    }

    @Test fun hardwareSurfacePipelineWhenSupported() {
        val r = planner.plan(cfg { it.video { v -> v.copy(engine = EngineChoice.AUTOMATIC) } }, Fixtures.ctx(resolver = Fixtures.resolver()))
        assertEquals(PipelineKind.HARDWARE, r.summary.pipeline)
        assertEquals("c2.vendor.hevc.encoder", r.plan.video!!.encoder)
        assertEquals("c2.vendor.hevc.decoder", r.plan.video!!.hwDecoder)
    }

    @Test fun cpuFiltersSelectHybridPipeline() {
        val c = cfg { it.video { v -> v.copy(filters = v.filters.copy(denoise = FilterStrength.LIGHT)) } }
        val r = planner.plan(c, Fixtures.ctx(resolver = Fixtures.resolver()))
        assertEquals(PipelineKind.HYBRID, r.summary.pipeline)
    }

    @Test fun hardwareRejectedSizeFallsBackToSoftwareWithReason() {
        val r = planner.plan(cfg(), Fixtures.ctx(resolver = Fixtures.resolver(sizeOk = false)))
        assertEquals(PipelineKind.SOFTWARE, r.summary.pipeline)
        assertTrue(r.summary.hardwareUnavailableReasons.any { it.contains("does not support") })
    }

    @Test fun av1HasNoHardwareOnTestDeviceAndUsesSvt() {
        val r = planner.plan(cfg { it.video { v -> v.copy(codec = VideoCodec.AV1) } }, Fixtures.ctx(resolver = Fixtures.resolver()))
        assertEquals("libsvtav1", r.plan.video!!.encoder)
        assertTrue(r.summary.hardwareUnavailableReasons.isNotEmpty())
    }

    @Test fun remuxCopiesVideo() {
        val r = planner.plan(cfg { it.video { v -> v.copy(mode = VideoMode.COPY) } }, Fixtures.ctx())
        assertEquals(PipelineKind.REMUX, r.summary.pipeline)
        assertEquals("copy", r.plan.video!!.mode)
    }

    @Test fun resolutionDownscaleKeepsAspectAndEvenDims() {
        val r = planner.plan(cfg { it.video { v -> v.copy(resolution = ResolutionChoice.R720, codec = VideoCodec.H264) } }, Fixtures.ctx())
        assertEquals(1280, r.plan.video!!.width)
        assertEquals(720, r.plan.video!!.height)
    }

    @Test fun portraitVideoScalesShortSide() {
        val src = Fixtures.phoneVideo(rotation = 90)
        val r = planner.plan(cfg { it.video { v -> v.copy(resolution = ResolutionChoice.R720, codec = VideoCodec.H264) } }, Fixtures.ctx(src))
        val v = r.plan.video!!
        assertEquals(720, minOf(v.width, v.height))
        assertTrue(v.height > v.width)
    }

    @Test fun noUpscaleByDefault() {
        val src = Fixtures.phoneVideo(width = 1280, height = 720)
        val r = planner.plan(cfg { it.video { v -> v.copy(resolution = ResolutionChoice.R1080, codec = VideoCodec.H264) } }, Fixtures.ctx(src))
        assertEquals(720, r.plan.video!!.height)
    }

    @Test fun fpsCapProducesPeakMode() {
        val src = Fixtures.phoneVideo(fps = 60)
        val r = planner.plan(cfg { it.video { v -> v.copy(fps = FpsChoice.F30, fpsMode = FpsMode.PEAK) } }, Fixtures.ctx(src))
        assertEquals(30, r.plan.video!!.fpsNum / r.plan.video!!.fpsDen)
        assertEquals(30.0, r.summary.outputFps, 0.01)
    }

    @Test fun targetSizeComputesBudget() {
        val c = cfg { it.video { v -> v.copy(rateControl = RateControlMode.TARGET_SIZE, targetSizeMb = 20.0) } }
        val r = planner.plan(c, Fixtures.ctx())
        val b = r.summary.budget!!
        assertTrue(b.videoKbps in 2000..2700)
        val rc = r.plan.video!!.rateControl!!
        assertTrue(rc.bitrateKbps > 0)
        assertNotNull(r.summary.feasibility)
    }

    @Test fun hdrWithAn8BitOnlyEncoderAsksTheUser() {
        val src = Fixtures.phoneVideo(hdr = "hlg", bitDepth = 10)
        try {
            planner.plan(cfg(), Fixtures.ctx(src, Fixtures.resolver()))
            fail("HEVC (Kvazaar, 8-bit) cannot keep HDR; the user must choose")
        } catch (e: PlanningException) {
            assertTrue(e.issues.any { it.message.contains("HDR") && it.fixes.isNotEmpty() })
        }
    }

    @Test fun hdrPreservedWith10BitSoftwareEncoder() {
        val src = Fixtures.phoneVideo(hdr = "hlg", bitDepth = 10)
        val r = planner.plan(cfg { it.copy(container = Container.MKV).video { v -> v.copy(codec = VideoCodec.AV1) } }, Fixtures.ctx(src, Fixtures.resolver()))
        assertEquals(PipelineKind.SOFTWARE, r.summary.pipeline)
        assertEquals(10, r.plan.video!!.bitDepth)
    }

    @Test fun hdrTonemapProducesSdr() {
        val src = Fixtures.phoneVideo(hdr = "pq", bitDepth = 10)
        val r = planner.plan(cfg { it.video { v -> v.copy(hdr = HdrMode.TONEMAP_SDR, codec = VideoCodec.H264) } }, Fixtures.ctx(src))
        assertEquals(8, r.plan.video!!.bitDepth)
        assertEquals("tonemap", r.plan.video!!.hdrMode.lowercase().let { if (it.startsWith("tonemap")) "tonemap" else it })
    }

    @Test fun mp4ConvertsTextSubtitlesAndDropsBitmap() {
        val src = Fixtures.phoneVideo(subtitles = true)
        val r = planner.plan(cfg { it.copy(subtitles = it.subtitles.copy(mode = SubtitleMode.COPY)) }, Fixtures.ctx(src))
        assertEquals(1, r.plan.subtitles.size)
        assertEquals("mov_text", r.plan.subtitles.single().codec)
    }

    @Test fun mkvKeepsAllSubtitles() {
        val src = Fixtures.phoneVideo(subtitles = true)
        val r = planner.plan(cfg { it.copy(container = Container.MKV, subtitles = it.subtitles.copy(mode = SubtitleMode.COPY)) }, Fixtures.ctx(src))
        assertEquals(2, r.plan.subtitles.size)
    }

    @Test fun invalidConfigThrowsPlanningException() {
        try {
            planner.plan(cfg { it.copy(container = Container.WEBM).video { v -> v.copy(codec = VideoCodec.H264) } }, Fixtures.ctx())
            fail("expected PlanningException")
        } catch (e: PlanningException) {
            assertTrue(e.issues.isNotEmpty())
        }
    }

    @Test fun planIsDeterministicAndRoundTripsJson() {
        val a = planner.plan(cfg(), Fixtures.ctx()).plan
        val b = planner.plan(cfg(), Fixtures.ctx()).plan
        assertEquals(a.cacheKey(), b.cacheKey())
        val back = ExecutionPlan.fromJson(a.toJson())
        assertEquals(a, back)
    }

    @Test fun cacheKeyChangesWithSettings() {
        val a = planner.plan(cfg(), Fixtures.ctx()).plan
        val b = planner.plan(cfg { it.video { v -> v.copy(qualitySlider = 0.3f, qualityLevel = com.kuyamcliff.compressor.model.QualityLevel.CUSTOM) } }, Fixtures.ctx()).plan
        assertTrue(a.cacheKey() != b.cacheKey())
    }

    @Test fun everyBuiltInPresetPlansForATypicalPhoneVideo() {
        val ctx = Fixtures.ctx(resolver = Fixtures.resolver())
        for (p in BuiltInPresets.all) {
            var c = p.config
            if (c.video.rateControl == RateControlMode.TARGET_SIZE && c.video.targetSizeMb <= 0) c = c.video { it.copy(targetSizeMb = 25.0) }
            try {
                planner.plan(c, ctx)
            } catch (e: PlanningException) {
                fail("Preset ${p.id} failed: ${e.message}")
            }
        }
    }
}
