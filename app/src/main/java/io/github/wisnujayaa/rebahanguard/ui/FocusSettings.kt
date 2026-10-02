package io.github.wisnujayaa.rebahanguard.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.wisnujayaa.rebahanguard.core.FocusRules
import io.github.wisnujayaa.rebahanguard.core.Place
import io.github.wisnujayaa.rebahanguard.core.PlaceRules
import io.github.wisnujayaa.rebahanguard.service.DreamStore
import io.github.wisnujayaa.rebahanguard.service.FocusStore
import io.github.wisnujayaa.rebahanguard.service.PlaceStore

private data class AppEntry(val pkg: String, val label: String, val distracting: Boolean)

/** Re-reads permission state when the user comes back from Settings. */
@Composable
private fun rememberResumeCount(): Int {
    val owner = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) count++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return count
}

@Composable
fun FocusSettings() {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    val usage = remember(resumes) { FocusStore.hasUsageAccess(context) }
    val strict = remember(resumes) { FocusStore.isStrictEnabled(context) }
    var enabled by remember { mutableStateOf(FocusStore.enabled(context)) }
    var picking by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(FocusStore.blocked(context)) }
    val today = DreamStore.today()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Tone.Dusk)
            .border(1.dp, Tone.Hairline, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mode fokus", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saat sesi, jam target, deadline < 6 jam, atau jadwal jaga, aplikasi pengalih ditahan. " +
                        "Jatah “minta 5 menit” ${FocusRules.PASSES_PER_DAY}× sehari, setelah menunggu 30 detik.",
                    style = MaterialTheme.typography.bodySmall, color = Tone.Muted,
                )
            }
            Switch(enabled, { enabled = it; FocusStore.setEnabled(context, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Tone.Ink, checkedTrackColor = Tone.Lamp))
        }
        Status(
            ok = usage,
            okText = "Akses penggunaan diizinkan",
            todo = "Izinkan “Akses penggunaan” agar penjaga tahu aplikasi mana yang dibuka",
        ) { open(context, Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        if (io.github.wisnujayaa.rebahanguard.BuildConfig.STRICT_MODE) {
            Status(
                ok = strict,
                okText = "Mode ketat aktif: reaksi seketika, halaman Info aplikasi ditutup saat terlindungi",
                todo = "Mode ketat (opsional): Aksesibilitas › Rebahan Guard. Untuk aplikasi dari luar Play Store, " +
                    "buka dulu Info aplikasi › ⋮ › “Izinkan setelan terbatas”",
            ) { open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        } else {
            Text(
                "Edisi standar: tanpa mode ketat (Aksesibilitas), agar tidak diblokir Play Protect. " +
                    "Mode fokus tetap bekerja lewat Akses penggunaan.",
                style = MaterialTheme.typography.bodySmall, color = Tone.Muted,
            )
        }
        val attempts = FocusStore.attempts(context).on(today)
        val passes = FocusStore.passes(context).left(today)
        Text("Hari ini: mencoba membuka aplikasi pengalih $attempts kali · sisa jatah 5 menit: $passes",
            style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        TextButton(onClick = { picking = !picking }) {
            Text(if (picking) "Tutup daftar aplikasi" else "Pilih aplikasi pengalih (${blocked.size})")
        }
        if (picking) AppPicker(blocked) { blocked = it; FocusStore.setBlocked(context, it) }
    }
}

@Composable
private fun Status(ok: Boolean, okText: String, todo: String, onFix: () -> Unit) {
    if (ok) {
        Text("✓ $okText", style = MaterialTheme.typography.bodySmall, color = Tone.Mint)
    } else {
        Text(todo, style = MaterialTheme.typography.bodySmall, color = Tone.Blanket,
            modifier = Modifier.clickable(onClick = onFix).padding(vertical = 4.dp))
    }
}

private fun open(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun AppPicker(blocked: Set<String>, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val apps = remember { launchableApps(context) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Disarankan: media sosial, video, game, berita, dan toko online.", style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        for (a in apps) {
            val on = a.pkg in blocked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChange(if (on) blocked - a.pkg else blocked + a.pkg) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (a.distracting && !on) Text("disarankan  ", style = MaterialTheme.typography.labelSmall, color = Tone.Lamp)
                Text(if (on) "Ditahan" else "Bebas", style = MaterialTheme.typography.labelMedium, color = if (on) Tone.Blanket else Tone.Muted)
            }
            HorizontalDivider(color = Tone.Hairline)
        }
    }
}

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return try {
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && it.packageName !in FocusRules.NEVER_BLOCK }
            .map { info ->
                AppEntry(
                    pkg = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    distracting = info.packageName in FocusRules.DEFAULT_BLOCKED || info.category in FocusRules.DISTRACTING_CATEGORIES &&
                        info.category != ApplicationInfo.CATEGORY_UNDEFINED,
                )
            }
            .sortedWith(compareByDescending<AppEntry> { it.distracting }.thenBy { it.label.lowercase() })
    } catch (e: Exception) {
        emptyList()
    }
}

private fun resultText(p: Place?) = if (p != null) "Tempat \u201C${p.name}\u201D disimpan (${p.bssids.size} Wi-Fi)." else
    "Lokasi belum didapat. Nyalakan GPS dan Wi-Fi, tunggu sebentar, lalu coba lagi."

/** Saved places for "Tempat" habits, recorded while standing there. */
@Composable
fun PlacesSettings() {
    val context = LocalContext.current
    var places by remember { mutableStateOf(PlaceStore.load(context)) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            message = "Mencari lokasi…"
            saveHere(context, name) { saved -> places = PlaceStore.load(context); message = resultText(saved) }
        } else {
            message = "Izin lokasi ditolak."
        }
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
        Text("Tempat", style = MaterialTheme.typography.titleSmall)
        Text(
            "Simpan tempat saat kamu sedang berada di sana (perpus, lapangan, masjid). Yang disimpan: " +
                "titik GPS dan alamat Wi-Fi di sekitar. Lokasimu tidak direkam selama perjalanan.",
            style = MaterialTheme.typography.bodySmall, color = Tone.Muted,
        )
        for (p in places) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} · ${p.bssids.size} Wi-Fi", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { places = places - p; PlaceStore.save(context, places) }) { Text("Hapus", color = Tone.Blanket) }
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Perpustakaan UI") })
        TextButton(onClick = {
            if (name.isBlank()) {
                message = "Beri nama tempatnya dulu."
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                message = "Mencari lokasi…"
            saveHere(context, name) { saved -> places = PlaceStore.load(context); message = resultText(saved) }
            } else {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }) { Text("Simpan lokasi ini") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.Lamp) }
    }
}

@SuppressLint("MissingPermission") // only called after the permission check
private fun saveHere(context: Context, name: String, done: (Place?) -> Unit) {
    val clean = name.trim().take(40)
    if (clean.isEmpty()) return done(null)
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION") runCatching { wifi.startScan() } // throttled by Android; cached results still help

    fun finish(loc: android.location.Location?) {
        val bssids = runCatching { PlaceRules.normalizeBssids(wifi.scanResults.mapNotNull { it.BSSID }) }.getOrDefault(emptySet())
        if (loc == null && bssids.isEmpty()) return done(null)
        val place = Place(
            id = System.currentTimeMillis(), name = clean,
            lat = loc?.latitude ?: 0.0, lon = loc?.longitude ?: 0.0, accuracyM = loc?.accuracy ?: 500f, bssids = bssids,
        )
        PlaceStore.save(context, PlaceStore.load(context).filterNot { it.name == clean } + place)
        done(place)
    }

    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
        .minByOrNull { it.accuracy }
    if (recent != null || providers.isEmpty() || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
        finish(recent)
        return
    }
    var finished = false
    var pending = providers.size
    for (p in providers) {
        lm.getCurrentLocation(p, null, ContextCompat.getMainExecutor(context)) { loc ->
            pending--
            if (finished) return@getCurrentLocation
            if (loc != null || pending == 0) {
                finished = true
                finish(loc)
            }
        }
    }
}
