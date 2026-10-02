package io.github.wisnujayaa.rebahanguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.wisnujayaa.rebahanguard.AppSurface
import io.github.wisnujayaa.rebahanguard.BottomNav
import io.github.wisnujayaa.rebahanguard.ImportantToday
import io.github.wisnujayaa.rebahanguard.SettingsPanel
import io.github.wisnujayaa.rebahanguard.StatusLines
import io.github.wisnujayaa.rebahanguard.Tab
import io.github.wisnujayaa.rebahanguard.TodayHeader
import io.github.wisnujayaa.rebahanguard.UrgentCallout
import io.github.wisnujayaa.rebahanguard.core.CheckReason
import io.github.wisnujayaa.rebahanguard.core.CheckReport
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.PlanCategory
import io.github.wisnujayaa.rebahanguard.core.PlanItem
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.core.Schedule
import io.github.wisnujayaa.rebahanguard.service.GuardSettings
import io.github.wisnujayaa.rebahanguard.service.GuardStatus
import io.github.wisnujayaa.rebahanguard.service.LastCheck
import io.github.wisnujayaa.rebahanguard.service.PlanStore
import io.github.wisnujayaa.rebahanguard.service.DreamStore
import io.github.wisnujayaa.rebahanguard.core.Dream
import io.github.wisnujayaa.rebahanguard.core.DreamBook
import io.github.wisnujayaa.rebahanguard.core.DreamRules
import io.github.wisnujayaa.rebahanguard.core.Habit
import io.github.wisnujayaa.rebahanguard.core.HabitUnit
import io.github.wisnujayaa.rebahanguard.core.Proof
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders key parts of the UI through the real app root ([AppSurface]) and saves PNGs to
 * app/build/outputs/roborazzi. CI uploads them so every change can be looked at, e.g. to catch
 * unreadable text on the paper or the night palette, which unit tests can't see.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    // No JUnit Timeout rule here: it runs the test on another thread, and Robolectric's main
    // looper may only be driven from the test's main thread.
    @get:Rule val compose = createComposeRule()

    @Before
    fun seedPlan() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        PlanStore.update(context) {
            listOf(
                PlanItem(1, "Laporan praktikum DDP2", PlanCategory.KULIAH, dueWallMs = now + 4 * hour + 12 * 60_000, createdAtMs = 1),
                PlanItem(2, "Cuci baju", PlanCategory.RUMAH, dueWallMs = now + 2 * hour, createdAtMs = 2),
                PlanItem(3, "Latihan listening 30 menit", PlanCategory.BELAJAR, important = true, createdAtMs = 3),
                PlanItem(4, "Baca bab 4 Matematika Diskret", PlanCategory.BELAJAR, dueWallMs = now + 50 * hour, important = true, createdAtMs = 4),
                PlanItem(5, "Rapikan Google Drive", PlanCategory.LAINNYA, createdAtMs = 5),
                PlanItem(6, "Kuis PBP", PlanCategory.KULIAH, doneAtMs = now - hour, createdAtMs = 6),
            )
        }
        val today = DreamStore.today()
        DreamStore.update(context) {
            var b = DreamBook(
                dreams = listOf(Dream(1, "Kuliah S2 di luar negeri", "Aku ingin membuktikan pada diriku sendiri bahwa aku bisa lebih dari ini.", "IELTS 7.0 sebelum Mei 2027", createdDay = today - 10)),
                habits = listOf(
                    Habit(10, 1, "Latihan listening", HabitUnit.MINUTES, 30, windowStart = 19 * 60 + 30, windowEnd = 21 * 60, proof = Proof.DESK, createdDay = today - 10),
                    Habit(11, 1, "Baca bab buku", HabitUnit.PAGES, 20, proof = Proof.PHOTO, createdDay = today - 10),
                    Habit(12, 1, "Lari pagi", HabitUnit.STEPS, 3000, proof = Proof.MOVE, createdDay = today - 10),
                ),
            )
            for (d in today - 4 until today) b = DreamRules.addProgress(b, 10, d, 30, 3)
            b = DreamRules.addProgress(b, 10, today, 10, 3)
            DreamRules.addProgress(b, 12, today, 3200, 3)
        }
    }

    private fun shot(name: String, padded: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            AppSurface {
                if (padded) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                } else {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val todayContent: @Composable () -> Unit = {
        TodayHeader(liveAngle = 38f, thresholdDeg = -5f)
        UrgentCallout(onOpenPlan = {})
        StatusLines(GuardStatus(running = true, phase = Phase.WATCHING, pose = Pose.UPRIGHT))
        HabitsToday(onOpenDreams = {})
        ImportantToday(onOpenPlan = {})
        BottomNav(selected = Tab.TODAY, onSelect = {})
    }

    @Test
    fun today() = shot("today", content = todayContent)

    @Test
    @Config(qualifiers = "+night")
    fun today_night() = shot("today_night", content = todayContent)

    @Test
    fun plan() = shot("plan", padded = false) { PlanTab() }

    @Test
    @Config(qualifiers = "+night")
    fun plan_night() = shot("plan_night", padded = false) { PlanTab() }

    @Test
    fun dreams() = shot("dreams", padded = false) {
        val check = LastCheck(
            atMillis = 0, faceWidthRatio = null, rollDeg = null, headTiltDeg = null, lying = false,
            report = CheckReport(CheckReason.TOO_DARK, frames = 12, meanLuma = 21f, usedRingLight = true),
        )
        DreamsTab(refreshKey = 0, checks = listOf(check), onStartSession = { _, _ -> })
    }

    @Test
    @Config(qualifiers = "+night")
    fun dreams_night() = shot("dreams_night", padded = false) {
        DreamsTab(refreshKey = 0, checks = emptyList(), onStartSession = { _, _ -> })
    }

    @Test
    fun dream_editor() = shot("dream_editor") {
        DreamEditor(initial = null, onSave = {}, onArchive = null, onCancel = {})
    }

    @Test
    fun habit_editor() = shot("habit_editor") {
        HabitEditor(dreamId = 1, initial = null, onSave = {}, onDelete = null, onCancel = {})
    }

    @Test
    fun dial_sitting() = shot("dial_sitting") { ScreenAngleDial(elevationDeg = 38f, thresholdDeg = -5f, alarming = false) }

    @Test
    fun dial_prone() = shot("dial_prone") { ScreenAngleDial(elevationDeg = 70f, thresholdDeg = -5f, alarming = false, proneDeg = 55f) }

    @Test
    @Config(qualifiers = "+night")
    fun dial_lying_night() = shot("dial_lying_night") { ScreenAngleDial(elevationDeg = -22f, thresholdDeg = 14f, alarming = false) }

    @Test
    fun status_warning() = shot("status_warning") {
        StatusLines(GuardStatus(running = true, phase = Phase.ALARMING, pose = Pose.FACE_DOWN, warningUntilElapsedMs = 1))
    }

    @Test
    fun settings_panel() = shot("settings_panel") {
        SettingsPanel(
            settings = GuardSettings(schedule = Schedule(enabled = true, startMinute = 19 * 60, endMinute = 23 * 60), commitmentHours = 3),
            editable = true,
            committed = false,
            onChange = {},
        )
    }
}
