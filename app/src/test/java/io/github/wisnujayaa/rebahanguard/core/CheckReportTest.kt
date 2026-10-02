package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckReportTest {
    private val min = 0.15f
    private fun classify(
        face: FaceObservation? = null,
        frames: Int = 10,
        luma: Float = 120f,
        modelNotReady: Boolean = false,
        errors: Int = 0,
        camera: Boolean = false,
    ) = CheckReport.classify(face, min, frames, luma, modelNotReady, errors, camera)

    @Test
    fun aGoodFace_alwaysWins_evenInTheDark() =
        assertEquals(CheckReason.FACE_FOUND, classify(face = FaceObservation(0.3f, 0f), luma = 10f))

    @Test
    fun specificProblemsBeatTheGenericOne() {
        assertEquals(CheckReason.CAMERA_UNAVAILABLE, classify(camera = true))
        assertEquals(CheckReason.MODEL_NOT_READY, classify(modelNotReady = true))
        assertEquals(CheckReason.NO_FRAMES, classify(frames = 0, luma = Float.NaN))
        assertEquals(CheckReason.FACE_TOO_SMALL, classify(face = FaceObservation(0.05f, 0f)))
        assertEquals(CheckReason.DETECTOR_ERROR, classify(errors = 10))
        assertEquals(CheckReason.TOO_DARK, classify(luma = 20f))
        assertEquals(CheckReason.NO_FACE, classify())
    }

    @Test
    fun garbageFace_isNotTreatedAsFound() =
        assertEquals(CheckReason.NO_FACE, classify(face = FaceObservation(Float.NaN, 0f)))
}
