package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.wisnujayaa.rebahanguard.core.FaceObservation
import io.github.wisnujayaa.rebahanguard.core.GuardConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Turns the front camera on for a short window, runs on-device face detection (ML Kit) on
 * each frame and reports the largest face it saw. Frames are analysed in memory and
 * discarded immediately — nothing is saved or sent anywhere.
 */
class FaceChecker(private val context: Context, private val config: GuardConfig) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
    )

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var session: Session? = null

    private class Session(val onResult: (FaceObservation?) -> Unit) {
        @Volatile var done = false
        var best: FaceObservation? = null
        var timeout: Runnable? = null
    }

    val isChecking: Boolean get() = session != null

    /** Must be called on the main thread. [onResult] is delivered on the main thread. */
    fun check(owner: LifecycleOwner, onResult: (FaceObservation?) -> Unit) {
        if (session != null) return
        val s = Session(onResult)
        session = s

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (s.done) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val useCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // A service has no display; ROTATION_0 = the phone's natural portrait frame,
                    // so the face roll we get back is relative to the phone's long axis.
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                useCase.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy, s) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
                analysis = useCase

                val timeout = Runnable { finish(s) }
                s.timeout = timeout
                mainHandler.postDelayed(timeout, config.cameraWindowMs)
            } catch (e: Exception) {
                // Camera busy (e.g. a video call), no front camera, permission revoked...
                Log.w(TAG, "Camera check failed", e)
                finish(s)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy, s: Session) {
        val mediaImage = proxy.image
        if (s.done || mediaImage == null) {
            proxy.close()
            return
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) proxy.width else proxy.height
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(input)
            .addOnSuccessListener { faces ->
                val largest = faces.maxByOrNull { it.boundingBox.width() } ?: return@addOnSuccessListener
                val observation = FaceObservation(
                    faceWidthRatio = largest.boundingBox.width().toFloat() / uprightWidth,
                    rollDeg = largest.headEulerAngleZ,
                )
                mainHandler.post { onObservation(s, observation) }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Face detection failed", e) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onObservation(s: Session, observation: FaceObservation) {
        if (s.done) return
        val best = s.best
        if (best == null || observation.faceWidthRatio > best.faceWidthRatio) s.best = observation
        // A clearly visible face is enough evidence — stop early to save battery.
        if (observation.faceWidthRatio >= config.minFaceWidthRatio) finish(s)
    }

    private fun finish(s: Session) {
        if (s.done) return
        s.done = true
        stopCamera(s)
        s.onResult(s.best)
    }

    /** Stops an in-flight check without reporting a result. */
    fun cancel() {
        val s = session ?: return
        s.done = true
        stopCamera(s)
    }

    private fun stopCamera(s: Session) {
        s.timeout?.let { mainHandler.removeCallbacks(it) }
        analysis?.let { useCase ->
            useCase.clearAnalyzer()
            provider?.unbind(useCase)
        }
        analysis = null
        if (session === s) session = null
    }

    fun release() {
        cancel()
        detector.close()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "FaceChecker"
    }
}
