package io.github.wisnujayaa.rebahanguard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.wisnujayaa.rebahanguard.AppSurface
import io.github.wisnujayaa.rebahanguard.SettingsPanel
import io.github.wisnujayaa.rebahanguard.StatusLines
import io.github.wisnujayaa.rebahanguard.core.CheckReason
import io.github.wisnujayaa.rebahanguard.core.CheckReport
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.core.Schedule
import io.github.wisnujayaa.rebahanguard.service.GuardSettings
import io.github.wisnujayaa.rebahanguard.service.GuardStatus
import io.github.wisnujayaa.rebahanguard.service.LastCheck
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders key parts of the UI through the real app root ([AppSurface]) and saves PNGs to
 * app/build/outputs/roborazzi. CI uploads them so every change can be looked at, e.g. to catch
 * the "black text on a dark background" class of bug that unit tests can't see.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    // No JUnit Timeout rule here: it runs the test on another thread, and Robolectric's main
    // looper may only be driven from the test's main thread.
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AppSurface {
                Column(Modifier.padding(20.dp)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun dial_sitting() = shot("dial_sitting") { ScreenAngleDial(elevationDeg = 38f, thresholdDeg = -5f, alarming = false) }

    @Test
    fun dial_lying() = shot("dial_lying") { ScreenAngleDial(elevationDeg = -22f, thresholdDeg = 14f, alarming = false) }

    @Test
    fun status_warning() = shot("status_warning") {
        StatusLines(GuardStatus(running = true, phase = Phase.ALARMING, pose = Pose.FACE_DOWN, warningUntilElapsedMs = 1))
    }

    @Test
    fun status_watching_withHistory() = shot("status_watching") {
        val check = LastCheck(
            atMillis = 0, faceWidthRatio = null, rollDeg = null, headTiltDeg = null, lying = false,
            report = CheckReport(CheckReason.TOO_DARK, frames = 12, meanLuma = 21f, usedRingLight = true),
        )
        StatusLines(GuardStatus(running = true, phase = Phase.WATCHING, pose = Pose.SIDEWAYS, lastCheck = check))
        CheckHistory(listOf(check))
    }

    @Test
    fun settings_panel() = shot("settings_panel") {
        SettingsPanel(
            settings = GuardSettings(schedule = Schedule(enabled = true, startMinute = 22 * 60, endMinute = 5 * 60), commitmentHours = 8),
            editable = true,
            committed = false,
            onChange = {},
        )
    }
}
