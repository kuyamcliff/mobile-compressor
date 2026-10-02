package com.kuyamcliff.compressor.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kuyamcliff.compressor.engine.QualityMetrics
import com.kuyamcliff.compressor.model.CompressionConfig
import com.kuyamcliff.compressor.preview.ComparisonPoint
import com.kuyamcliff.compressor.preview.PreviewResult
import com.kuyamcliff.compressor.ui.compare.recommendedPoint
import com.kuyamcliff.compressor.ui.components.ChoiceChips
import com.kuyamcliff.compressor.ui.components.SwitchRow
import com.kuyamcliff.compressor.ui.info.OnboardingScreen
import com.kuyamcliff.compressor.ui.theme.CompressorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun disabledChipExplainsWhyAndDoesNotSelect() {
        var selected = "A"
        rule.setContent {
            CompressorTheme {
                var s by remember { mutableStateOf("A") }
                ChoiceChips(listOf("A", "B"), s, { it }, { s = it; selected = it }, disabledReason = { if (it == "B") "B is not supported here" else null })
            }
        }
        rule.onNodeWithText("B").performClick()
        rule.onNodeWithText("B is not supported here").assertExists()
        assertEquals("A", selected)
    }

    @Test fun enabledChipSelects() {
        var selected = "A"
        rule.setContent { CompressorTheme { ChoiceChips(listOf("A", "B"), selected, { it }, { selected = it }) } }
        rule.onNodeWithText("B").performClick()
        assertEquals("B", selected)
    }

    @Test fun switchRowToggles() {
        rule.setContent {
            CompressorTheme {
                var on by remember { mutableStateOf(false) }
                SwitchRow("Fast start", on, { on = it }, subtitle = "moov first")
            }
        }
        rule.onNode(isToggleable()).assertIsOff()
        rule.onNode(isToggleable()).performClick()
        rule.onNode(isToggleable()).assertIsOn()
    }

    @Test fun onboardingCompletes() {
        var done = false
        rule.setContent { CompressorTheme { OnboardingScreen(onDone = { done = true }) } }
        rule.onNodeWithText("Private, offline compression").assertExists()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Get started").performClick()
        assertTrue(done)
    }

    @Test fun ladderRecommendationPicksSmallestNearBest() {
        fun pt(label: String, bytes: Long, ssim: Double) = ComparisonPoint(
            label, CompressionConfig(),
            PreviewResult("", "", 0, 0, 0, 1, 0, 0.0, 0.0, "software", "x", 0, 0.0, QualityMetrics(psnr = 40.0, ssim = ssim)),
            bytes,
        )
        val pts = listOf(pt("high", 100, 0.990), pt("balanced", 60, 0.987), pt("small", 30, 0.95))
        assertEquals("balanced", recommendedPoint(pts)!!.label)
    }
}
