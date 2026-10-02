package io.github.wisnujayaa.rebahanguard.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.wisnujayaa.rebahanguard.core.PinHash
import io.github.wisnujayaa.rebahanguard.core.Totp
import io.github.wisnujayaa.rebahanguard.service.PartnerStore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SetupStep {
    data object Idle : SetupStep
    data class Qr(val secret: ByteArray) : SetupStep
    data object Pin : SetupStep
    data object Remove : SetupStep
}

/**
 * The trusted partner who holds the off switch. Setting one up is free; changing or removing one
 * needs the partner's own code, otherwise removing them would be the easy way out.
 */
@Composable
fun PartnerSection(editable: Boolean, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val hasTotp = remember(refresh) { PartnerStore.hasTotp(context) }
    val hasPin = remember(refresh) { PartnerStore.hasPin(context) }
    var name by remember(refresh) { mutableStateOf(PartnerStore.name(context) ?: "") }
    var step by remember { mutableStateOf<SetupStep>(SetupStep.Idle) }
    val hasPartner = hasTotp || hasPin

    fun done() {
        step = SetupStep.Idle
        refresh++
        onChanged()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Teman pengawas", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasPartner) {
                "${name.ifBlank { "Temanmu" }} memegang kunci penjaga " +
                    "(${listOfNotNull("kode Authenticator".takeIf { hasTotp }, "PIN".takeIf { hasPin }).joinToString(" dan ")}). " +
                    "Selama penjaga menyala, hanya kodenya yang bisa mematikannya."
            } else {
                "Serahkan tombol mati ke orang yang kamu percaya. Saat kamu malas, kamu harus " +
                    "minta izin ke dia dulu."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Muted,
        )

        when (val s = step) {
            SetupStep.Idle -> {
                if (!hasPartner) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Nama teman") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!hasTotp) {
                        TextButton(enabled = editable && (hasPartner || name.isNotBlank()), onClick = {
                            if (!hasPartner) PartnerStore.setName(context, name)
                            step = SetupStep.Qr(Totp.newSecret())
                        }) { Text("Pasang Authenticator") }
                    }
                    if (!hasPin) {
                        TextButton(enabled = editable && (hasPartner || name.isNotBlank()), onClick = {
                            if (!hasPartner) PartnerStore.setName(context, name)
                            step = SetupStep.Pin
                        }) { Text("Pasang PIN") }
                    }
                    if (hasPartner) {
                        TextButton(enabled = editable, onClick = { step = SetupStep.Remove }) { Text("Hapus teman") }
                    }
                }
            }

            is SetupStep.Qr -> QrSetup(
                secret = s.secret,
                partnerName = name.ifBlank { "teman" },
                onSaved = ::done,
                onCancel = {
                    s.secret.fill(0)
                    done()
                },
            )

            SetupStep.Pin -> PinSetup(onSaved = ::done, onCancel = ::done)

            SetupStep.Remove -> {
                Text("Minta temanmu memasukkan kodenya untuk menghapus dirinya sebagai pengawas.")
                PartnerCodeField(
                    actionLabel = "Hapus teman",
                    onVerified = {
                        PartnerStore.remove(context)
                        done()
                    },
                )
                TextButton(onClick = ::done) { Text("Batal") }
            }
        }
    }
}

@Composable
private fun QrSetup(secret: ByteArray, partnerName: String, onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(secret) { qrBitmap(Totp.otpauthUri(secret, partnerName), 720) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // No screenshots while the secret is on screen: a saved picture would be a copy of the key.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Text(
        "1. Berikan HP ini ke $partnerName. 2. Dia membuka Google Authenticator (atau aplikasi " +
            "sejenis) dan memindai kode ini. 3. Dia mengetik kode 6 digit yang muncul di HP-nya. " +
            "Kode QR ini hanya ditampilkan sekali dan tidak bisa di-screenshot.",
        style = MaterialTheme.typography.bodySmall,
    )
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = "Kode QR untuk aplikasi Authenticator",
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
        label = { Text("Kode dari HP $partnerName") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(shape = CircleShape, onClick = {
            val hmac: (ByteArray) -> ByteArray = { msg ->
                Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }.doFinal(msg)
            }
            if (!Totp.verify(hmac, code, System.currentTimeMillis() / 1000)) {
                error = "Kodenya belum cocok. Pastikan jam kedua HP benar, lalu coba kode terbaru."
            } else if (PartnerStore.saveTotpSecret(context, secret)) {
                onSaved()
            } else {
                error = "HP ini menolak menyimpan kunci dengan aman. Pakai PIN saja."
            }
        }) { Text("Simpan") }
        TextButton(onClick = onCancel) { Text("Batal") }
    }
}

@Composable
private fun PinSetup(onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Text(
        "Berikan HP ini ke temanmu. Dia mengetik PIN atau kata sandi (minimal ${PinHash.MIN_LENGTH} karakter) " +
            "yang hanya dia yang tahu. Jangan mengintip.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.take(64); error = null },
        label = { Text("PIN teman") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = again,
        onValueChange = { again = it.take(64); error = null },
        label = { Text("Ulangi PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(shape = CircleShape, enabled = !saving, onClick = {
            when {
                !PinHash.isAcceptable(pin) -> error = "Minimal ${PinHash.MIN_LENGTH} karakter."
                pin != again -> error = "Kedua PIN tidak sama."
                else -> {
                    saving = true
                    scope.launch {
                        withContext(Dispatchers.Default) { PartnerStore.savePin(context, pin) } // slow hash
                        saving = false
                        onSaved()
                    }
                }
            }
        }) { Text(if (saving) "Menyimpan…" else "Simpan") }
        TextButton(onClick = onCancel) { Text("Batal") }
    }
}

/** Where the partner types their authenticator code or PIN. */
@Composable
fun PartnerCodeField(actionLabel: String, onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = code,
        onValueChange = { code = it.take(64); error = null },
        label = { Text("Kode Authenticator atau PIN teman") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(shape = CircleShape, enabled = !checking && code.isNotBlank(), onClick = {
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { PartnerStore.verify(context, code) }
            checking = false
            when (result) {
                PartnerStore.Result.Ok -> onVerified()
                PartnerStore.Result.Wrong -> error = "Kode salah."
                is PartnerStore.Result.LockedOut ->
                    error = "Terlalu banyak percobaan. Coba lagi dalam ${(result.remainingMs + 59_999) / 60_000} menit."
            }
            code = ""
        }
    }) { Text(if (checking) "Memeriksa…" else actionLabel) }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
