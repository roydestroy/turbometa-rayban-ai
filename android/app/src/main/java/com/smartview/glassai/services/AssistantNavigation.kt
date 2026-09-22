package com.smartview.glassai.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartview.glassai.R
import java.lang.ref.WeakReference

object AssistantNavigation {
    var foregroundActivity = WeakReference<Activity>(null)
    fun launch(context: Context, destination: String, mode: String): String {
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode"))
            .setPackage("com.google.android.apps.maps")
        check(intent.resolveActivity(context.packageManager) != null) { "Install Google Maps to start navigation." }
        val activity = foregroundActivity.get()
        if (activity != null && !activity.isFinishing) {
            activity.startActivity(intent)
            pauseWake(context)
            return "Opened Google Maps for $destination. Wake listening is paused so Maps can use the glasses audio."
        }
        check(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            "Open TurboMeta to start navigation, or allow TurboMeta notifications and ask again."
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("assistant_navigation", "Start navigation", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 31, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(1031, NotificationCompat.Builder(context, "assistant_navigation")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Start navigation to $destination")
            .setContentText("Tap to open Google Maps").setContentIntent(open)
            .addAction(0, "Start navigation", open).setAutoCancel(true).build())
        pauseWake(context)
        return "Navigation is NOT started. Tap the Start navigation notification to open Google Maps for $destination. Wake listening is paused."
    }
    private fun pauseWake(context: Context) {
        context.startService(Intent(context, VoskWakeWordService::class.java).setAction(VoskWakeWordService.ACTION_STOP))
    }
}
