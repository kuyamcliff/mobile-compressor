package com.kuyamcliff.compressor.queue

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kuyamcliff.compressor.CompressorApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while the queue has work.
 * The encoding itself runs in the application-scoped [QueueManager]; this
 * service only owns the foreground state and the progress notification, so a
 * job never depends on an Activity being open (PRD §50).
 *
 * Type: mediaProcessing on Android 15+, dataSync below. Both have a system time
 * budget on recent Android versions; [onTimeout] stops cleanly when it is used up.
 */
class EncodingService : LifecycleService() {

    private val container get() = (application as CompressorApp).container

    override fun onCreate() {
        super.onCreate()
        running = true
        container.notifications.createChannels()
        goForeground(container.queue.snapshot.value)
        lifecycleScope.launch {
            container.queue.snapshot.collectLatest { s ->
                if (s.idle) {
                    // collectLatest cancels this wait if new work shows up.
                    delay(3000)
                    stopSelfCleanly()
                } else {
                    container.notifications.updateProgress(container.notifications.progress(s))
                }
            }
        }
    }

    private fun goForeground(s: QueueSnapshot) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, Notifications.PROGRESS_ID, container.notifications.progress(s), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground(container.queue.snapshot.value)
        val jobId = intent?.getLongExtra(EXTRA_JOB_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_PAUSE -> lifecycleScope.launch { if (jobId > 0) container.queue.pause(jobId) else container.queue.pauseAll() }
            ACTION_RESUME -> lifecycleScope.launch { if (jobId > 0) container.queue.resume(jobId) else container.queue.resumeAll() }
            ACTION_CANCEL -> lifecycleScope.launch { if (jobId > 0) container.queue.cancel(jobId) }
        }
        container.queue.kick()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /** Android 15+: the foreground-service time budget for this type ran out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        lifecycleScope.launch {
            container.queue.onForegroundTimeout()
            stopSelfCleanly()
        }
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        const val ACTION_PAUSE = "com.kuyamcliff.compressor.action.PAUSE"
        const val ACTION_RESUME = "com.kuyamcliff.compressor.action.RESUME"
        const val ACTION_CANCEL = "com.kuyamcliff.compressor.action.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
    }
}
