package com.kuyamcliff.compressor.domain

import com.kuyamcliff.compressor.Fixtures
import com.kuyamcliff.compressor.model.AudioCodec
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.model.Container
import com.kuyamcliff.compressor.model.RateControlMode
import com.kuyamcliff.compressor.model.SubtitleMode
import com.kuyamcliff.compressor.model.VideoCodec
import com.kuyamcliff.compressor.model.VideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kuyamcliff.compressor.data.repo.withoutPrivatePaths
import java.time.LocalDateTime

class ValidationTest {
    private fun errors(c: CompressionConfig, src: com.kuyamcliff.compressor.model.SourceInfo = Fixtures.phoneVideo()) =
        ConfigValidator.validate(c, Fixtures.ctx(src)).filter { it.severity == Severity.ERROR }

    @Test fun defaultConfigIsValid() = assertTrue(errors(CompressionConfig()).isEmpty())

    @Test fun codecContainerMismatchIsErrorWithFix() {
        val c = CompressionConfig(container = Container.WEBM, video = CompressionConfig().video.copy(codec = VideoCodec.HEVC))
        val issues = errors(c)
        assertTrue(issues.isNotEmpty())
        val fix = issues.flatMap { it.fixes }.firstOrNull()
        assertTrue("an automatic fix should be offered", fix != null)
        assertTrue(errors(fix!!.apply(c)).isEmpty())
    }

    @Test fun audioCodecContainerMismatch() {
        val c = CompressionConfig(container = Container.WEBM, video = CompressionConfig().video.copy(codec = VideoCodec.VP9), audio = CompressionConfig().audio.copy(codec = AudioCodec.AAC))
        assertTrue(errors(c).isNotEmpty())
    }

    @Test fun targetSizeRequired() {
        val c = CompressionConfig(video = CompressionConfig().video.copy(rateControl = RateControlMode.TARGET_SIZE, targetSizeMb = 0.0))
        assertTrue(errors(c).any { it.code == "target_missing" })
    }

    @Test fun burnInRequiresTranscode() {
        val c = CompressionConfig(video = CompressionConfig().video.copy(mode = VideoMode.COPY), subtitles = CompressionConfig().subtitles.copy(mode = SubtitleMode.BURN))
        assertTrue(errors(c, Fixtures.phoneVideo(subtitles = true)).any { it.code == "burn_copy" })
    }

    @Test fun burnWithoutSubtitlesIsError() {
        val c = CompressionConfig(subtitles = CompressionConfig().subtitles.copy(mode = SubtitleMode.BURN))
        assertTrue(errors(c).any { it.code == "burn_none" })
    }

    @Test fun badOptionSyntax() {
        val c = CompressionConfig(video = CompressionConfig().video.copy(advancedOptions = mapOf("" to "x")))
        assertTrue(errors(c).any { it.code == "bad_option_syntax" })
    }

    @Test fun replaceOriginalIsWarned() {
        val c = CompressionConfig(output = CompressionConfig().output.copy(replaceOriginal = true))
        val all = ConfigValidator.validate(c, Fixtures.ctx())
        assertTrue(all.any { it.code == "replace_original" && it.severity == Severity.WARNING })
    }

    @Test fun compatibilityMatrix() {
        assertTrue(Compatibility.supports(Container.MP4, VideoCodec.HEVC))
        assertFalse(Compatibility.supports(Container.WEBM, VideoCodec.H264))
        assertTrue(Compatibility.canCopyAudio(Container.MKV, "flac"))
        assertFalse(Compatibility.canCopyAudio(Container.WEBM, "aac"))
        assertEquals("mov_text", Compatibility.subtitleSupport(Container.MP4).textCodec)
        assertTrue(Container.entries.all { c -> Compatibility.videoCodecs(c).isNotEmpty() })
    }
}

class FileNamingTest {
    private val now = LocalDateTime.of(2026, 1, 2, 3, 4, 5)

    @Test fun rendersTokens() {
        val name = FileNaming.render("{name}_{codec}_{resolution}_{date}", FileNaming.Values("Trip.MOV", "HEVC", "1080p", now = now), "mp4")
        assertEquals("Trip_HEVC_1080p_20260102_030405.mp4", name)
    }

    @Test fun sanitizesPathTraversalAndReservedNames() {
        assertEquals("a_b_c", FileNaming.sanitize("a/b\\c"))
        assertFalse(FileNaming.sanitize("../../etc/passwd").contains(".."))
        assertFalse(FileNaming.sanitize("../../etc/passwd").contains("/"))
        assertEquals("CON_", FileNaming.sanitize("CON"))
        assertEquals("video", FileNaming.sanitize("   ...  "))
        assertEquals("x_y", FileNaming.sanitize("x\u0000y"))
        assertTrue(FileNaming.sanitize("a".repeat(500)).length <= 120)
    }

    @Test fun emptyTemplateFallsBack() {
        assertEquals("clip_compressed.mkv", FileNaming.render("", FileNaming.Values("clip.mp4", now = now), "mkv"))
    }

    @Test fun extensionIsSanitized() {
        assertEquals("mp4", FileNaming.sanitizeExtension("../"))
        assertEquals("webm", FileNaming.sanitizeExtension("WebM"))
    }

    @Test fun numbering() {
        assertEquals("a (2).mp4", FileNaming.numbered("a.mp4", 2))
        assertEquals("noext (1)", FileNaming.numbered("noext", 1))
    }
}

class PresetsTest {
    @Test fun idsAreUnique() {
        val ids = BuiltInPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun defaultExists() = assertTrue(BuiltInPresets.all.any { it.id == BuiltInPresets.default.id })

    @Test fun presetConfigsAreInternallyCompatible() {
        for (p in BuiltInPresets.all) {
            val c = p.config
            if (c.video.mode == VideoMode.TRANSCODE) assertTrue(p.id, Compatibility.supports(c.container, c.video.codec))
            if (!c.audio.removeAudio) assertTrue(p.id, Compatibility.supports(c.container, c.audio.codec))
        }
    }

    @Test fun presetsSerializeRoundTrip() {
        for (p in BuiltInPresets.all) {
            val text = com.kuyamcliff.compressor.data.repo.AppJson.encodeConfig(p.config)
            assertEquals(p.id, p.config, com.kuyamcliff.compressor.data.repo.AppJson.decodeConfig(text))
        }
    }

    @Test fun exportStripsPrivatePaths() {
        val c = CompressionConfig(
            output = CompressionConfig().output.copy(folderUri = "content://tree/secret"),
            subtitles = CompressionConfig().subtitles.copy(external = listOf(com.kuyamcliff.compressor.model.ExternalSubtitle("content://x/y.srt", "y.srt"))),
        )
        val clean = c.withoutPrivatePaths()
        assertEquals(null, clean.output.folderUri)
        assertTrue(clean.subtitles.external.isEmpty())
    }
}
