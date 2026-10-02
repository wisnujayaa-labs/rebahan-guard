package io.github.wisnujayaa.rebahanguard.ui

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.wisnujayaa.rebahanguard.core.Cloze
import io.github.wisnujayaa.rebahanguard.core.Habit
import io.github.wisnujayaa.rebahanguard.core.HabitUnit
import io.github.wisnujayaa.rebahanguard.core.PageProgress
import io.github.wisnujayaa.rebahanguard.core.QuizCard
import io.github.wisnujayaa.rebahanguard.service.DreamStore
import io.github.wisnujayaa.rebahanguard.service.QuizStore
import io.github.wisnujayaa.rebahanguard.service.SessionStore
import java.io.File
import kotlin.random.Random

/**
 * "Foto hasil": a photo taken live in the app (never picked from the gallery). For reading, the
 * text on the page is recognised on the phone: a new page counts as progress, and a sentence
 * from it becomes a fill-in-the-blank question for later. Photos stay on the phone and are
 * deleted after 7 days.
 */
@Composable
fun PhotoProof(habit: Habit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf("Arahkan kamera ke halaman yang sedang kamu baca, atau ke hasil kerjamu.") }
    var busy by remember { mutableStateOf(false) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    EditorCard {
        Kicker("Foto hasil · ${habit.title}")
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(Tone.Hairline),
        ) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { this.controller = controller } },
                modifier = Modifier.matchParentSize(),
            )
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                shape = CircleShape,
                onClick = {
                    busy = true
                    status = "Memotret…"
                    val file = newPhotoFile(context)
                    controller.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                status = "Membaca teks di foto…"
                                recognise(context, Uri.fromFile(file), habit) { msg ->
                                    status = msg
                                    busy = false
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                status = "Gagal memotret. Coba lagi."
                                busy = false
                            }
                        },
                    )
                },
            ) { Text(if (busy) "Tunggu…" else "Potret") }
            TextButton(onClick = onClose) { Text("Selesai", color = Tone.Muted) }
        }
    }
}

private fun photosDir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

private fun newPhotoFile(context: Context): File {
    val dir = photosDir(context)
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    dir.listFiles()?.filter { it.lastModified() < weekAgo }?.forEach { it.delete() }
    return File(dir, "proof-${System.currentTimeMillis()}.jpg")
}

private fun recognise(context: Context, uri: Uri, habit: Habit, done: (String) -> Unit) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = try {
        InputImage.fromFilePath(context, uri)
    } catch (e: Exception) {
        done("Foto tidak bisa dibaca. Coba lagi.")
        return
    }
    recognizer.process(image)
        .addOnSuccessListener { result -> done(evaluate(context, habit, result.text)) }
        .addOnFailureListener {
            // No text model yet (Play services still downloading it): the photo is still proof.
            DreamStore.addProgress(context, habit.id, if (habit.unit == HabitUnit.PAGES) 0 else 1, 2)
            done("Foto tersimpan sebagai bukti. Pengenal teks belum siap, jadi halaman belum dihitung.")
        }
        .addOnCompleteListener { recognizer.close() }
}

private fun evaluate(context: Context, habit: Habit, text: String): String {
    val today = DreamStore.today()
    val strength = maxOf(2, SessionStore.strength(habit.id, today).coerceAtMost(3))
    if (habit.unit != HabitUnit.PAGES) {
        DreamStore.addProgress(context, habit.id, 1, strength)
        return "Bukti tersimpan."
    }
    if (!PageProgress.looksLikePage(text)) return "Teks di foto terlalu sedikit untuk dihitung sebagai halaman. Dekatkan kamera."
    val previous = QuizStore.lastPage(context, habit.id)
    if (!PageProgress.isNewPage(previous, text)) return "Ini halaman yang sama seperti foto sebelumnya. Lanjutkan membaca dulu."
    val pages = run {
        val a = previous?.let { PageProgress.pageNumber(it) }
        val b = PageProgress.pageNumber(text)
        if (a != null && b != null && b > a) (b - a).coerceAtMost(50) else 1
    }
    QuizStore.setLastPage(context, habit.id, text)
    DreamStore.addProgress(context, habit.id, pages, strength)
    val cards = Cloze.make(text, Random(System.nanoTime()), max = 2)
    QuizStore.update(context) { list ->
        list + cards.mapIndexed { i, (prompt, answer) ->
            QuizCard(id = System.currentTimeMillis() * 10 + i, habitId = habit.id, prompt = prompt, answer = answer, createdDay = today)
        }
    }
    return "Halaman baru tercatat (+$pages). ${if (cards.isEmpty()) "" else "${cards.size} pertanyaan dibuat untuk kuis nanti."}"
}
