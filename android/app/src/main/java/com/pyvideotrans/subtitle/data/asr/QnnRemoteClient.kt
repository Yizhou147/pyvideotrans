package com.pyvideotrans.subtitle.data.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class QnnRemoteClient(private val context: Context) {

    suspend fun transcribe(
        request: QnnRequest,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
    ): TranscriptionOutcome = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val bound = AtomicBoolean(false)
            val unbound = AtomicBoolean(false)
            val remote = AtomicReference<Messenger?>(null)
            val remoteBinder = AtomicReference<IBinder?>(null)
            val resultFile = File(request.resultPath)
            resultFile.delete()

            lateinit var connection: ServiceConnection
            lateinit var deathRecipient: IBinder.DeathRecipient

            fun cleanup() {
                remoteBinder.getAndSet(null)?.let { binder ->
                    runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                }
                if (bound.get() && unbound.compareAndSet(false, true)) {
                    runCatching { context.unbindService(connection) }
                }
            }

            fun finishFailure(error: Throwable) {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                continuation.resumeWithException(error)
            }

            fun resumeFromResult() {
                val outcome = runCatching {
                    val payload = json.decodeFromString(
                        QnnResultPayload.serializer(),
                        resultFile.readText(),
                    )
                    TranscriptionOutcome(
                        result = payload.result,
                        elapsedMs = payload.elapsedMs,
                        modelLoadMs = payload.modelLoadMs,
                    )
                }
                outcome.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = {
                        continuation.resumeWithException(
                            IllegalStateException("无法读取 NPU 识别结果：${it.message}"),
                        )
                    },
                )
            }

            fun finishSuccess() {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                resumeFromResult()
            }

            fun finishProcessDeath(message: String) {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                if (resultFile.isFile) {
                    resumeFromResult()
                } else {
                    // QNN aborts with _Exit() on any backend error, so the only
                    // clues left behind are the dying process' native stderr file
                    // and its logcat lines.
                    val detail = listOf(
                        readNativeDiagTail(context),
                        readNativeLogTail(),
                    ).filter { it.isNotBlank() }.joinToString(" | ")
                    val full = if (detail.isBlank()) message else "$message 底层日志：$detail"
                    continuation.resumeWithException(QnnProcessDiedException(full))
                }
            }

            val replyHandler = Handler(Looper.getMainLooper()) { message ->
                if (finished.get()) return@Handler true
                when (message.what) {
                    QnnAsrProtocol.MSG_PROGRESS -> onProgress(message.arg1.coerceIn(0, 100))
                    QnnAsrProtocol.MSG_LOG -> {
                        message.data?.getString(QnnAsrProtocol.KEY_LOG)?.let(onLog)
                    }
                    QnnAsrProtocol.MSG_RESULT -> finishSuccess()
                    QnnAsrProtocol.MSG_CANCELLED -> {
                        if (finished.compareAndSet(false, true)) {
                            cleanup()
                            continuation.cancel(CancellationException("NPU 识别已取消"))
                        }
                    }
                    QnnAsrProtocol.MSG_ERROR -> {
                        val error = message.data?.getString(QnnAsrProtocol.KEY_ERROR)
                            ?: "NPU 识别失败"
                        finishFailure(IllegalStateException(error))
                    }
                }
                true
            }
            val reply = Messenger(replyHandler)

            deathRecipient = IBinder.DeathRecipient {
                finishProcessDeath(
                    "NPU 进程异常退出（可能是不兼容的 QNN 运行库或驱动）。已停止 NPU 识别。",
                )
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (finished.get()) {
                        cleanup()
                        return
                    }
                    if (service == null) {
                        finishFailure(IllegalStateException("无法连接 NPU 进程"))
                        return
                    }
                    remoteBinder.set(service)
                    runCatching { service.linkToDeath(deathRecipient, 0) }
                    val messenger = Messenger(service)
                    remote.set(messenger)
                    val args = Bundle().apply {
                        putString(QnnAsrProtocol.KEY_MODEL_NAME, request.modelName)
                        putString(QnnAsrProtocol.KEY_ENCODER, request.encoderPath)
                        putString(QnnAsrProtocol.KEY_DECODER, request.decoderPath)
                        putString(QnnAsrProtocol.KEY_TOKENS, request.tokensPath)
                        putString(QnnAsrProtocol.KEY_VAD, request.vadPath)
                        putString(QnnAsrProtocol.KEY_WAV, request.wavPath)
                        putString(QnnAsrProtocol.KEY_RESULT_PATH, request.resultPath)
                        putString(QnnAsrProtocol.KEY_LANGUAGE, request.language)
                        putInt(QnnAsrProtocol.KEY_THREADS, request.threads)
                    }
                    try {
                        messenger.send(
                            Message.obtain(null, QnnAsrProtocol.MSG_START).apply {
                                data = args
                                replyTo = reply
                            },
                        )
                    } catch (t: RemoteException) {
                        finishFailure(
                            IllegalStateException("无法向 NPU 进程发送任务：${t.message}"),
                        )
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finishProcessDeath("NPU 进程已断开（QNN 初始化失败或进程被终止）")
                }

                override fun onBindingDied(name: ComponentName?) {
                    finishProcessDeath("NPU 进程绑定失效（QNN 初始化失败或进程被终止）")
                }

                override fun onNullBinding(name: ComponentName?) {
                    finishFailure(IllegalStateException("NPU 服务未能启动"))
                }
            }

            val intent = Intent().setClassName(context, SERVICE_CLASS)
            val started = runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrElse { t ->
                finishFailure(IllegalStateException("无法启动 NPU 进程：${t.message}", t))
                false
            }
            bound.set(started)
            if (!started) {
                finishFailure(
                    IllegalStateException("无法绑定 NPU 服务，请确认安装包包含 NPU 运行库"),
                )
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    runCatching {
                        remote.get()?.send(Message.obtain(null, QnnAsrProtocol.MSG_CANCEL))
                    }
                    cleanup()
                    runCatching { context.stopService(intent) }
                }
            }
        }
    }

    private companion object {
        const val SERVICE_CLASS = "com.pyvideotrans.subtitle.data.asr.QnnAsrService"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private val NATIVE_LOG_MARKERS = listOf(
    "QnnAsrService",
    "sherpa-onnx",
    "QNN",
    "HTP",
    "cdsprpc",
    "fastrpc",
    "onnxruntime",
    "UnsatisfiedLink",
    "AndroidRuntime",
    "FATAL",
    "libc",
    "DEBUG",
)

private fun readNativeLogTail(): String = runCatching {
    val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-v", "brief", "-t", "400"),
    )
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    val matched = text.lines()
        .map { it.trim() }
        .filter { line -> NATIVE_LOG_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } }
        .takeLast(8)
        .joinToString(" / ")
    matched.take(700)
}.getOrDefault("")

/** Tail of the native stderr the NPU process redirected before starting QNN. */
private fun readNativeDiagTail(context: Context): String = runCatching {
    val file = File(context.applicationContext.cacheDir, QnnAsrProtocol.NATIVE_DIAG_FILE)
    if (!file.isFile) return ""
    val tail = file.readText()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeLast(12)
        .joinToString(" / ")
    tail.take(1600)
}.getOrDefault("")
