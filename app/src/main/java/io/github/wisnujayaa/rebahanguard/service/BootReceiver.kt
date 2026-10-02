package io.github.wisnujayaa.rebahanguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.wisnujayaa.rebahanguard.MainActivity
import io.github.wisnujayaa.rebahanguard.R

/**
 * Android doesn't let an app start a camera foreground service right after boot, so restarting
 * the phone switches the guard off. If a commitment is still running, nag the user to reopen the
 * app — opening it restarts the guard automatically.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Remind only if the guard was running under protection when the phone went down.
        if (!CommitmentStore.isActive(context) && !(PartnerStore.hasPartner(context) && CommitmentStore.isSessionOpen(context))) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pengingat komitmen", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guard)
            .setContentTitle("Penjaga mati karena HP restart")
            .setContentText("Penjagamu masih seharusnya aktif. Ketuk untuk menyalakannya lagi.")
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed: nothing more we can do here.
        }
    }

    companion object {
        const val CHANNEL_ID = "commitment"
        const val NOTIFICATION_ID = 2

        fun dismiss(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }
}
