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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pyvideotrans.subtitle.App
import com.pyvideotrans.subtitle.MainActivity
import com.pyvideotrans.subtitle.R
import com.pyvideotrans.subtitle.util.LogBus
import kotlinx.coroutines.CoroutineExceptionHandler
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

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            // Nothing here is worth losing a recognition run over.
            LogBus.error("service", "通知线程异常：${error.javaClass.name}: ${error.message}")
        },
    )
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as App).subtitleJob.cancel()
            return START_NOT_STICKY
        }
        val job = (application as App).subtitleJob
        val established = runCatching {
            ensureChannel(this)
            startForegroundNotification(job.state.value.phase, job.state.value.progress)
        }.onFailure {
            LogBus.error("service", "前台服务未能建立：${it.javaClass.name}: ${it.message}")
        }.isSuccess
        if (!established) {
            // Ending the service is what keeps the platform from reporting it as never having
            // gone foreground, which is itself a crash.
            stopSelf()
            return START_NOT_STICKY
        }
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
        NotificationManagerCompat.from(this).runCatching {
            notify(NOTIFICATION_ID, buildNotification(this@SubtitleService, phase, progress))
        }.onFailure {
            LogBus.warn("service", "通知未能更新：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(timeoutType: Int, startId: Int) {
        // The platform caps mediaProcessing and dataSync at six hours in any 24. Saying it out
        // loud beats the ANR the framework raises if the service is still running seconds later;
        // the job itself lives in the application, so the run is not destroyed by this.
        LogBus.warn(
            "service",
            "前台服务到达系统时长上限（类型 $timeoutType），本轮改由唤醒锁继续",
        )
        stopSelf()
    }

    private fun startForegroundNotification(phase: String, progress: Int?) {
        val notification = buildNotification(this, phase, progress)
        // mediaProcessing only exists from Android 15 on; asking an older platform for it comes
        // back as "type none" and the whole call is refused. OEM ROMs also refuse a single type
        // outright, so try every type this service declares instead of betting on one.
        val types = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            }
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        var lastError: Throwable? = null
        for (type in types) {
            val attempt = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            }
            if (attempt.isSuccess) {
                LogBus.info("service", "前台服务已建立（类型 $type）")
                return
            }
            lastError = attempt.exceptionOrNull()
            LogBus.warn(
                "service",
                "前台服务类型 $type 被拒：${lastError?.javaClass?.name}: ${lastError?.message}",
            )
        }
        throw lastError ?: IllegalStateException("没有可用的前台服务类型")
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
