package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimationTest {
    @Test fun sliderBppRoundTrip() {
        for (s in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            assertEquals(s, Estimator.sliderForBpp(Estimator.bppForSlider(s)), 1e-4f)
        }
        assertTrue(Estimator.bppForSlider(0.9f) > Estimator.bppForSlider(0.1f))
    }

    @Test fun budgetSubtractsAudioAndOverhead() {
        val b = TargetSize.budget(25L * 1024 * 1024, 120.0, 128, 0, Container.MP4)
        val audio = 128_000 / 8 * 120L
        assertEquals(audio, b.audioBytes)
        assertTrue(b.videoBytes < 25L * 1024 * 1024 - audio)
        assertTrue("${b.videoKbps}", b.videoKbps in 1550..1625)
    }

    @Test fun impossibleWhenAudioExceedsTarget() {
        val b = TargetSize.budget(1L * 1024 * 1024, 600.0, 128, 0, Container.MP4)
        assertEquals(0, b.videoKbps)
        val f = TargetSize.feasibility(b, 600.0, Size(1920, 1080), 30.0, VideoCodec.HEVC, false, 1.0, 128, Container.MP4, Size(1920, 1080))
        assertEquals(QualityRisk.IMPOSSIBLE, f.risk)
    }

    @Test fun lowerResolutionLowersRisk() {
        val b = TargetSize.budget(10L * 1024 * 1024, 300.0, 64, 0, Container.MP4)
        val f = TargetSize.feasibility(b, 300.0, Size(1920, 1080), 30.0, VideoCodec.HEVC, false, 1.0, 64, Container.MP4, Size(1920, 1080))
        val opt720 = f.resolutionOptions.first { it.size.height == 720 }
        val opt1080 = f.resolutionOptions.first { it.size.height == 1080 }
        assertTrue(opt720.bppEq > opt1080.bppEq)
        assertTrue(opt720.risk.ordinal <= opt1080.risk.ordinal)
        assertTrue(f.recommendedTargetBytes > 10L * 1024 * 1024)
    }

    @Test fun riskThresholds() {
        assertEquals(QualityRisk.LOW, TargetSize.riskFor(0.08, 3000))
        assertEquals(QualityRisk.SEVERE, TargetSize.riskFor(0.008, 3000))
        assertEquals(QualityRisk.IMPOSSIBLE, TargetSize.riskFor(1.0, 10))
    }

    @Test fun makeSmallerTargetIsPercentOfOriginal() {
        assertEquals(25_000_000L, TargetSize.makeSmallerTarget(100_000_000L, 25))
        assertEquals(99_000_000L, TargetSize.makeSmallerTarget(100_000_000L, 150))
    }

    @Test fun estimateRangeContainsMidpoint() {
        for (basis in EstimateBasis.entries) {
            val r = Estimator.rangeFor(10_000_000, basis)
            assertTrue(r.lowBytes <= 10_000_000 && r.highBytes >= 10_000_000)
        }
    }

    @Test fun fpsSavingIsPositiveWhenReducing() {
        val (lo, hi) = Estimator.fpsSavingRange(60.0, 30.0)
        assertTrue(lo > 0 && hi >= lo && hi < 1)
    }
}
