package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseClassifierTest {
    private fun classify(x: Float, y: Float, z: Float) = PoseClassifier.classify(x, y, z)

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
    fun tinyReading_isUnknown() = assertEquals(Pose.UNKNOWN, classify(0.1f, 0.2f, 0.1f))
}
