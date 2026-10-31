package com.pyvideotrans.subtitle.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pyvideotrans.subtitle.App
import com.pyvideotrans.subtitle.MainActivity
import com.pyvideotrans.subtitle.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shows what a running job is doing while the app is out of sight.
 *
 * A foreground service is not subject to the background limits that would otherwise freeze
 * or kill a recognition run of tens of minutes, and its notification carries the phase, the
 * percentage and a cancel button. The job owns all state, so this service can be started and
 * stopped without any risk to the run itself.
 */
class SubtitleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as App).subtitleJob.cancel()
            return START_NOT_STICKY
        }
        val job = (application as App).subtitleJob
        ensureChannel(this)
        startForegroundNotification(job.state.value.phase, job.state.value.progress)
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                job.state.collect { state ->
                    postNotification(state.phase, state.progress)
                    if (!state.running) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** A denied notification permission must not stop the job, nor the service ending. */
    private fun postNotification(phase: String, progress: Int?) {
        // The permission only exists from Android 13 on; asking the older platforms about it
        // answers "denied", which would silently freeze the notification at its first text.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(this, phase, progress),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification(phase: String, progress: Int?) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this, phase, progress),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            },
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "subtitle_job"
        private const val ACTION_CANCEL = "com.pyvideotrans.subtitle.action.CANCEL_JOB"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SubtitleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SubtitleService::class.java))
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "字幕任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "识别进度与取消入口"
                    setShowBadge(false)
                },
            )
        }

        fun buildNotification(context: Context, phase: String, progress: Int?): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancel = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, SubtitleService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("生成中文字幕")
                .setContentText(phase)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setProgress(100, progress ?: 0, progress == null)
                .setContentIntent(open)
                .addAction(0, "取消", cancel)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
