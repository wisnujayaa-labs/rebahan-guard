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
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.wisnujayaa.rebahanguard.core.CheckReport
import io.github.wisnujayaa.rebahanguard.core.FaceObservation
import io.github.wisnujayaa.rebahanguard.core.GuardConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Set while the in-app photo screen holds the camera. */
object CameraGate {
    @Volatile var photoInUse = false
}

/**
 * Turns the front camera on for a short window, runs on-device face detection (ML Kit) on
 * each frame and reports the largest face it saw, plus a [CheckReport] explaining the result.
 * Frames are analysed in memory and discarded immediately — nothing is saved or sent anywhere.
 */
class FaceChecker(private val context: Context, private val config: GuardConfig) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // ACCURATE copes much better with tilted heads and faces seen from below.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .build()
    )

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var session: Session? = null

    private class Session(
        val onDark: () -> Unit,
        val onResult: (FaceObservation?, CheckReport) -> Unit,
    ) {
        @Volatile var done = false
        var best: FaceObservation? = null
        var timeout: Runnable? = null

        // Diagnostics, only touched on the main thread.
        var frames = 0
        var lumaSum = 0.0
        var detectorErrors = 0
        var modelNotReady = false
        var cameraUnavailable = false
        var darkSignalled = false
        var usedRingLight = false
    }

    val isChecking: Boolean get() = session != null

    /**
     * Must be called on the main thread. [onDark] fires (once) if the first frames are too dark,
     * so the caller can light the user's face; [onResult] is delivered on the main thread.
     */
    fun check(
        owner: LifecycleOwner,
        onDark: () -> Unit = {},
        onResult: (FaceObservation?, CheckReport) -> Unit,
    ) {
        if (session != null) return
        val s = Session(onDark, onResult)
        session = s

        // Start the clock immediately, not after the camera opens: if the camera provider
        // never becomes ready, the check still ends on time.
        val timeout = Runnable { finish(s) }
        s.timeout = timeout
        mainHandler.postDelayed(timeout, config.cameraWindowMs)

        // The photo-proof screen is using the (back) camera: don't fight it for the hardware.
        if (CameraGate.photoInUse) {
            s.cameraUnavailable = true
            finish(s)
            return
        }

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

                // Only our own previous use case: unbindAll() would also tear down the
                // photo-proof preview, which shares the app-wide camera provider.
                analysis?.let { cameraProvider.unbind(it) }
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
                analysis = useCase
            } catch (e: Exception) {
                // Camera busy (e.g. a video call), no front camera, permission revoked...
                Log.w(TAG, "Camera check failed", e)
                s.cameraUnavailable = true
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
        if (uprightWidth <= 0) {
            proxy.close()
            return
        }

        val luma = meanLuma(proxy)
        mainHandler.post { onFrame(s, luma) }

        try {
            val input = InputImage.fromMediaImage(mediaImage, rotation)
            detector.process(input)
                .addOnSuccessListener { faces ->
                    val largest = faces.maxByOrNull { it.boundingBox.width() }
                        ?: return@addOnSuccessListener
                    val observation = FaceObservation(
                        faceWidthRatio = largest.boundingBox.width().toFloat() / uprightWidth,
                        rollDeg = largest.headEulerAngleZ,
                        pitchDeg = largest.headEulerAngleX,
                        yawDeg = largest.headEulerAngleY,
                    )
                    mainHandler.post { onObservation(s, observation) }
                }
                .addOnFailureListener { e ->
                    val notReady = e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE
                    Log.w(TAG, "Face detection failed (modelNotReady=$notReady)", e)
                    mainHandler.post {
                        s.detectorErrors++
                        if (notReady) s.modelNotReady = true
                    }
                }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            // e.g. the detector was closed by release() while this frame was in flight.
            // Never let an exception escape the analysis thread: that would crash the app.
            Log.w(TAG, "Could not analyse frame", e)
            mainHandler.post { s.detectorErrors++ }
            proxy.close()
        }
    }

    /** Average brightness of the Y (luminance) plane, sampling every 16th pixel. */
    private fun meanLuma(proxy: ImageProxy): Float = try {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var sum = 0L
        var n = 0
        var y = 0
        while (y < proxy.height) {
            var x = 0
            while (x < proxy.width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xff // absolute get: position unchanged
                    n++
                }
                x += 16
            }
            y += 16
        }
        if (n == 0) Float.NaN else sum.toFloat() / n
    } catch (e: Exception) {
        Float.NaN
    }

    private fun onFrame(s: Session, luma: Float) {
        if (s.done) return
        s.frames++
        if (luma.isFinite()) s.lumaSum += luma
        if (!s.darkSignalled && luma.isFinite() && luma < CheckReport.DARK_LUMA) {
            s.darkSignalled = true
            s.usedRingLight = true
            s.onDark()
        }
    }

    private fun onObservation(s: Session, observation: FaceObservation) {
        if (s.done || !observation.isValid) return
        val best = s.best
        if (best == null || observation.faceWidthRatio > best.faceWidthRatio) s.best = observation
        // A clearly visible face is enough evidence — stop early to save battery.
        if (observation.faceWidthRatio >= config.minFaceWidthRatio) finish(s)
    }

    private fun finish(s: Session) {
        if (s.done) return
        s.done = true
        stopCamera(s)
        val meanLuma = if (s.frames > 0) (s.lumaSum / s.frames).toFloat() else Float.NaN
        val report = CheckReport(
            reason = CheckReport.classify(
                bestFace = s.best,
                minFaceWidthRatio = config.minFaceWidthRatio,
                frames = s.frames,
                meanLuma = meanLuma,
                modelNotReady = s.modelNotReady,
                detectorErrors = s.detectorErrors,
                cameraUnavailable = s.cameraUnavailable,
            ),
            frames = s.frames,
            meanLuma = meanLuma,
            usedRingLight = s.usedRingLight,
        )
        s.onResult(s.best, report)
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
