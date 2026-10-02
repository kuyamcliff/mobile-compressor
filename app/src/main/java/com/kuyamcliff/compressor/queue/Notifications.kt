package com.kuyamcliff.compressor.queue

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kuyamcliff.compressor.MainActivity
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.util.Format

/** Snapshot of the queue used to render the ongoing notification. */
data class QueueSnapshot(
    val activeJobId: Long? = null,
    val activeName: String? = null,
    val progress: Double = -1.0,
    val currentFps: Double = 0.0,
    val averageFps: Double = 0.0,
    val etaUs: Long = -1,
    val paused: Boolean = false,
    val statusText: String? = null,
    val running: Int = 0,
    val waiting: Int = 0,
) {
    val idle: Boolean get() = running == 0 && waiting == 0
}

class Notifications(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, context.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_progress_desc)
                setShowBadge(false)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_RESULTS, context.getString(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_results_desc)
            },
        )
    }

    private fun openApp(jobId: Long?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (jobId != null) putExtra(MainActivity.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getActivity(context, (jobId ?: 0L).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(action: String, jobId: Long?): PendingIntent {
        val i = Intent(context, EncodingService::class.java).setAction(action).putExtra(EncodingService.EXTRA_JOB_ID, jobId ?: -1L)
        return PendingIntent.getService(context, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(s: QueueSnapshot): Notification {
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(s.activeJobId))
        if (s.activeName == null) {
            b.setContentTitle(context.getString(R.string.notif_waiting_title))
                .setContentText(s.statusText ?: context.resources.getQuantityString(R.plurals.notif_waiting_jobs, s.waiting, s.waiting))
            return b.build()
        }
        val pct = if (s.progress >= 0) (s.progress * 100).toInt().coerceIn(0, 100) else -1
        val title = if (s.paused) context.getString(R.string.notif_paused_title) else context.getString(R.string.notif_compressing_title)
        val details = if (s.paused) (s.statusText ?: context.getString(R.string.status_paused)) else context.getString(
            R.string.notif_progress_details,
            Format.fps(s.currentFps), Format.fps(s.averageFps), if (s.etaUs >= 0) Format.duration(s.etaUs / 1000) else "—",
        )
        b.setContentTitle(title)
            .setContentText(if (pct >= 0) "${s.activeName} · $pct%" else s.activeName)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${s.activeName}\n${if (pct >= 0) "$pct% · " else ""}$details" +
                if (s.waiting > 0) "\n" + context.resources.getQuantityString(R.plurals.notif_waiting_jobs, s.waiting, s.waiting) else ""))
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
        if (s.paused) {
            b.addAction(0, context.getString(R.string.action_resume), serviceAction(EncodingService.ACTION_RESUME, s.activeJobId))
        } else {
            b.addAction(0, context.getString(R.string.action_pause), serviceAction(EncodingService.ACTION_PAUSE, s.activeJobId))
        }
        b.addAction(0, context.getString(R.string.action_cancel), serviceAction(EncodingService.ACTION_CANCEL, s.activeJobId))
        return b.build()
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun result(jobId: Long, title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(jobId))
            .build()
        runCatching { nm.notify(RESULT_BASE + (jobId % 10_000).toInt(), n) }
    }

    fun updateProgress(n: Notification) {
        if (canPost()) runCatching { nm.notify(PROGRESS_ID, n) }
    }

    companion object {
        const val CH_PROGRESS = "encoding_progress"
        const val CH_RESULTS = "encoding_results"
        const val PROGRESS_ID = 1001
        const val RESULT_BASE = 2000
    }
}
