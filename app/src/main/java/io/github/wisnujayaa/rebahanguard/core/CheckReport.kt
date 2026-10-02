package io.github.wisnujayaa.rebahanguard.core

/** Why a camera check ended the way it did — so failures can be measured instead of guessed. */
enum class CheckReason(val label: String) {
    FACE_FOUND("wajah terlihat"),
    NO_FACE("tidak ada wajah"),
    TOO_DARK("terlalu gelap"),
    FACE_TOO_SMALL("wajah terlalu jauh/kecil"),
    MODEL_NOT_READY("model wajah belum siap"),
    DETECTOR_ERROR("deteksi gagal"),
    NO_FRAMES("kamera tidak mengirim gambar"),
    CAMERA_UNAVAILABLE("kamera tidak tersedia"),
}

/**
 * Summary of one camera check.
 *
 * @param meanLuma average brightness of the frames, 0 (black) … 255 (white); NaN if none arrived.
 */
data class CheckReport(
    val reason: CheckReason,
    val frames: Int,
    val meanLuma: Float,
    val usedRingLight: Boolean,
) {
    companion object {
        /** Below this average brightness the face detector rarely finds anything. */
        const val DARK_LUMA = 45f

        /**
         * Picks the most useful explanation for a check. A found face always wins; otherwise the
         * most specific known problem is reported.
         */
        fun classify(
            bestFace: FaceObservation?,
            minFaceWidthRatio: Float,
            frames: Int,
            meanLuma: Float,
            modelNotReady: Boolean,
            detectorErrors: Int,
            cameraUnavailable: Boolean,
        ): CheckReason = when {
            cameraUnavailable -> CheckReason.CAMERA_UNAVAILABLE
            bestFace != null && bestFace.isValid && bestFace.faceWidthRatio >= minFaceWidthRatio -> CheckReason.FACE_FOUND
            modelNotReady -> CheckReason.MODEL_NOT_READY
            frames == 0 -> CheckReason.NO_FRAMES
            bestFace != null && bestFace.isValid -> CheckReason.FACE_TOO_SMALL
            detectorErrors > 0 && detectorErrors >= frames -> CheckReason.DETECTOR_ERROR
            meanLuma.isFinite() && meanLuma < DARK_LUMA -> CheckReason.TOO_DARK
            else -> CheckReason.NO_FACE
        }
    }
}
