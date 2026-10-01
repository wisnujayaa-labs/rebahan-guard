package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.random.Random

class PoseClassifierTest {
    private fun classify(
        x: Float,
        y: Float,
        z: Float,
        lyingElevationDeg: Float = PoseClassifier.DEFAULT_LYING_ELEVATION_DEG,
    ) = PoseClassifier.classify(x, y, z, lyingElevationDeg)

    // ------------------------------------------------------------ normal cases

    @Test
    fun flatOnTable_isFaceUp() = assertEquals(Pose.FACE_UP, classify(0f, 0f, 9.81f))

    @Test
    fun screenTowardFloor_isFaceDown() = assertEquals(Pose.FACE_DOWN, classify(0f, 0f, -9.81f))

    @Test
    fun heldOverheadWhileOnBack_isFaceDown() = assertEquals(Pose.FACE_DOWN, classify(0f, 4f, -8.5f))

    @Test
    fun proppedOnPillow_phoneAlmostUpright_tiltedTowardFace_isFaceDown() {
        // Reported bug: lying with the phone "tegak / agak tegak" was missed. Screen 15° below
        // the horizon = the user is looking slightly UP at the phone.
        val down15 = Math.toRadians(15.0)
        assertEquals(
            Pose.FACE_DOWN,
            classify(0f, (9.81 * Math.cos(down15)).toFloat(), (-9.81 * Math.sin(down15)).toFloat()),
        )
    }

    @Test
    fun sittingAndLookingDownAtThePhone_isNotSuspicious() {
        // Screen tilted 40° toward the ceiling: the usual way people hold a phone when sitting.
        val up40 = Math.toRadians(40.0)
        val pose = classify(0f, (9.81 * Math.cos(up40)).toFloat(), (9.81 * Math.sin(up40)).toFloat())
        assertEquals(Pose.UPRIGHT, pose)
    }

    @Test
    fun thresholdIsConfigurable() {
        val down10 = Math.toRadians(10.0)
        val y = (9.81 * Math.cos(down10)).toFloat()
        val z = (-9.81 * Math.sin(down10)).toFloat()
        assertEquals(Pose.FACE_DOWN, classify(0f, y, z, lyingElevationDeg = -5f))
        assertEquals(Pose.UPRIGHT, classify(0f, y, z, lyingElevationDeg = -30f))
    }

    @Test
    fun measuredAngles_matchKnownOrientations() {
        val upright = PoseClassifier.measure(0f, 9.81f, 0f)
        assertEquals(0f, upright.screenElevationDeg, 0.01f)
        assertEquals(0f, upright.inPlaneRotationDeg, 0.01f)

        val sideways = PoseClassifier.measure(9.81f, 0f, 0f)
        assertEquals(90f, sideways.inPlaneRotationDeg, 0.01f)

        val upsideDown = PoseClassifier.measure(0f, -9.81f, 0f)
        assertEquals(180f, kotlin.math.abs(upsideDown.inPlaneRotationDeg), 0.01f)

        val flat = PoseClassifier.measure(0f, 0f, 9.81f)
        assertEquals(90f, flat.screenElevationDeg, 0.01f)
        assertTrue(flat.inPlaneRotationDeg.isNaN()) // "upright" is meaningless when flat
    }

    @Test
    fun onItsSide_isSideways() {
        assertEquals(Pose.SIDEWAYS, classify(9.81f, 0f, 0f))
        assertEquals(Pose.SIDEWAYS, classify(-9.5f, 1f, 2f))
    }

    @Test
    fun normalHandHeld_isUpright() {
        assertEquals(Pose.UPRIGHT, classify(0f, 9.81f, 0f))
        assertEquals(Pose.UPRIGHT, classify(0f, 7.5f, 6.3f)) // tilted back toward the face
    }

    @Test
    fun upsideDown_isNotSuspicious() = assertEquals(Pose.UPSIDE_DOWN, classify(0f, -9.81f, 0f))

    // ------------------------------------------------------------ broken sensor data

    @Test
    fun tinyReading_isUnknown() = assertEquals(Pose.UNKNOWN, classify(0.1f, 0.2f, 0.1f))

    @Test
    fun zeroVector_isUnknown() = assertEquals(Pose.UNKNOWN, classify(0f, 0f, 0f))

    @Test
    fun nanOrInfinity_isUnknown() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (b in bad) {
            assertEquals(Pose.UNKNOWN, classify(b, 0f, 9.81f))
            assertEquals(Pose.UNKNOWN, classify(0f, b, 9.81f))
            assertEquals(Pose.UNKNOWN, classify(0f, 0f, b))
        }
    }

    @Test
    fun impossiblyStrongReading_isUnknown() {
        assertEquals(Pose.UNKNOWN, classify(0f, 0f, -200f)) // a hard knock, not a pose
        assertEquals(Pose.UNKNOWN, classify(Float.MAX_VALUE, 0f, 0f)) // x² overflows to Infinity
    }

    @Test
    fun brokenReadingsAreNeverSuspicious() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 1e30f)
        for (b in bad) assertTrue(!classify(b, b, b).isSuspicious)
    }

    // ------------------------------------------------------------ properties (randomized)

    @Test
    fun randomVectors_neverThrow_andAgreeWithTheMath() {
        val rnd = Random(42)
        repeat(100_000) {
            val x = rnd.nextFloat() * 40f - 20f
            val y = rnd.nextFloat() * 40f - 20f
            val z = rnd.nextFloat() * 40f - 20f
            val pose = classify(x, y, z)
            val m = sqrt(x * x + y * y + z * z)
            if (m < 1f || m > 50f) {
                assertEquals(Pose.UNKNOWN, pose)
            } else {
                // The one rule that must always hold: face-down iff the screen points below the
                // threshold angle.
                // Same float arithmetic as the production code, so boundary samples agree exactly.
                val elevation = asin((z / m).coerceIn(-1f, 1f)) * (180.0 / Math.PI).toFloat()
                assertEquals(
                    "($x,$y,$z)",
                    elevation < PoseClassifier.DEFAULT_LYING_ELEVATION_DEG,
                    pose == Pose.FACE_DOWN,
                )
            }
        }
    }

    @Test
    fun scalingTheVector_doesNotChangeThePose() {
        // A weaker/stronger reading in the same direction is the same orientation.
        val rnd = Random(7)
        repeat(20_000) {
            val x = rnd.nextFloat() * 2f - 1f
            val y = rnd.nextFloat() * 2f - 1f
            val z = rnd.nextFloat() * 2f - 1f
            val m = sqrt(x * x + y * y + z * z)
            if (m < 0.2f) return@repeat
            val a = classify(x / m * 5f, y / m * 5f, z / m * 5f)
            val b = classify(x / m * 20f, y / m * 20f, z / m * 20f)
            assertEquals(a, b)
        }
    }
}
