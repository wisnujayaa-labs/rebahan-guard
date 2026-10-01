package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.math.sqrt

class PoseClassifierTest {
    private fun classify(x: Float, y: Float, z: Float) = PoseClassifier.classify(x, y, z)

    // ------------------------------------------------------------ normal cases

    @Test
    fun flatOnTable_isFaceUp() = assertEquals(Pose.FACE_UP, classify(0f, 0f, 9.81f))

    @Test
    fun screenTowardFloor_isFaceDown() = assertEquals(Pose.FACE_DOWN, classify(0f, 0f, -9.81f))

    @Test
    fun heldOverheadWhileOnBack_isFaceDown() = assertEquals(Pose.FACE_DOWN, classify(0f, 4f, -8.5f))

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
                // The one rule that must always hold: face-down iff the screen points down > 30°.
                assertEquals("($x,$y,$z)", z / m < -0.5f, pose == Pose.FACE_DOWN)
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
