package com.pyvideotrans.subtitle.data.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.pyvideotrans.subtitle.data.model.QnnModelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

class QnnAsrService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper()) { message ->
        onMessage(message)
        true
    }
    private val messenger = Messenger(mainHandler)

    @Volatile
    private var engine: QnnWhisperEngine? = null

    @Volatile
    private var reply: Messenger? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        engine?.cancel()
        val hadRunningJob = running
        reply = null
        executor.shutdownNow()
        super.onDestroy()
        if (hadRunningJob) {
            // Native QNN inference cannot be interrupted. The service process is
            // disposable and dedicated to NPU work, so terminate it instead of
            // letting a cancelled context keep the HTP busy for the next job.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun onMessage(message: Message) {
        when (message.what) {
            QnnAsrProtocol.MSG_START -> startJob(message)
            QnnAsrProtocol.MSG_CANCEL -> {
                Log.i(TAG, "收到取消请求")
                engine?.cancel()
            }
        }
    }

    private fun startJob(message: Message) {
        val bundle = message.data ?: return
        val client = message.replyTo
        if (client == null) {
            Log.w(TAG, "缺少 replyTo，无法启动任务")
            return
        }
        if (running) {
            sendError(client, "NPU 进程正忙，请等待当前任务结束")
            return
        }
        val request = QnnRequest(
            modelName = bundle.getString(QnnAsrProtocol.KEY_MODEL_NAME).orEmpty(),
            encoderPath = bundle.getString(QnnAsrProtocol.KEY_ENCODER).orEmpty(),
            decoderPath = bundle.getString(QnnAsrProtocol.KEY_DECODER).orEmpty(),
            tokensPath = bundle.getString(QnnAsrProtocol.KEY_TOKENS).orEmpty(),
            vadPath = bundle.getString(QnnAsrProtocol.KEY_VAD).orEmpty(),
            wavPath = bundle.getString(QnnAsrProtocol.KEY_WAV).orEmpty(),
            resultPath = bundle.getString(QnnAsrProtocol.KEY_RESULT_PATH).orEmpty(),
            language = bundle.getString(QnnAsrProtocol.KEY_LANGUAGE).orEmpty(),
            threads = bundle.getInt(QnnAsrProtocol.KEY_THREADS, 4),
        )
        val validationError = validate(request)
        if (validationError != null) {
            sendError(client, validationError)
            return
        }

        running = true
        reply = client
        try {
            executor.execute { runJob(request) }
        } catch (t: Throwable) {
            running = false
            reply = null
            sendError(client, "无法启动 NPU 工作线程：${t.message}")
        }
    }

    private fun validate(request: QnnRequest): String? {
        val status = QnnSupport.status(applicationContext)
        if (!status.ready) {
            return "NPU 不可用：${status.reason()}"
        }
        val required = listOf(
            request.encoderPath,
            request.decoderPath,
            request.tokensPath,
            request.vadPath,
            request.wavPath,
        )
        val missing = required.firstOrNull { !File(it).isFile }
        if (missing != null) {
            return "NPU 模型或音频文件不存在：$missing"
        }
        if (request.resultPath.isBlank()) {
            return "NPU 结果路径为空"
        }
        return null
    }

    private fun runJob(request: QnnRequest) {
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        collectNativeDiagnostics(nativeDir)
        val activeEngine = QnnWhisperEngine(nativeDir)
        engine = activeEngine
        try {
            sendLog("NPU 任务开始：${request.modelName.ifBlank { "Whisper" }}")
            val encoder = File(request.encoderPath)
            val files = QnnModelFiles(
                dir = encoder.parentFile ?: nativeDir,
                encoder = encoder,
                decoder = File(request.decoderPath),
                tokens = File(request.tokensPath),
            )
            val outcome = activeEngine.transcribe(
                files = files,
                vadOnnx = File(request.vadPath),
                wavFile = File(request.wavPath),
                language = request.language,
                threads = request.threads,
                onProgress = { percent -> sendProgress(percent) },
                onLog = { line ->
                    Log.i(TAG, line)
                    sendLog(line)
                },
            )

            val payload = QnnResultPayload(
                result = outcome.result,
                elapsedMs = outcome.elapsedMs,
                modelLoadMs = outcome.modelLoadMs,
            )
            val resultFile = File(request.resultPath)
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(json.encodeToString(QnnResultPayload.serializer(), payload))
            sendResult(request.resultPath)
        } catch (ce: CancellationException) {
            Log.i(TAG, "NPU 任务已取消")
            sendPlain(QnnAsrProtocol.MSG_CANCELLED)
        } catch (t: Throwable) {
            Log.e(TAG, "NPU 识别失败", t)
            sendError(reply, "NPU 识别失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            engine = null
            running = false
            reply = null
        }
    }

    /**
     * QNN ends a failed init with `_Exit()`, which leaves no Java trace. Native
     * stderr therefore goes to a file in the shared cache directory and the
     * loader's verdict on every runtime library is sent to the log panel before
     * inference starts, so a device failure is readable without adb.
     */
    private fun collectNativeDiagnostics(nativeDir: File) {
        val diagFile = File(applicationContext.cacheDir, QnnAsrProtocol.NATIVE_DIAG_FILE)
        val redirected = QnnNativeProbe.startNativeLog(diagFile)
        sendLog(
            if (redirected) {
                "原生日志：${diagFile.absolutePath}"
            } else {
                "原生日志不可用：native stderr 重定向失败"
            },
        )

        // FastRPC first: the HTP stub needs libcdsprpc.so, which is not in the
        // app namespace search path. Loading it by full vendor path registers the
        // soname, so report the loader verdict and then re-check the stub.
        emit(diagFile, "FastRPC 环境 ${cdspProbeReport(applicationContext, nativeDir)}")
        cdspProbeTargets().forEach { target ->
            QnnNativeProbe.probeCdspRpc(target)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { emit(diagFile, "FastRPC 命令行检查 $it") }
        }
        val preload = cdspRpcPaths()
        if (preload.isEmpty()) {
            emit(diagFile, "FastRPC 加载 应用侧找不到任何 libcdsprpc.so")
        } else {
            QnnNativeProbe.probePaths(preload).forEach { emit(diagFile, "FastRPC 加载 $it") }
        }
        val (usable, _) = cdspLibraryPaths(nativeDir)
        if (usable.isNotBlank() && QnnNativeProbe.setEnv("LD_LIBRARY_PATH", usable)) {
            emit(diagFile, "已设置 LD_LIBRARY_PATH=$usable")
        }

        QnnNativeProbe
            .probe(nativeDir, QnnSupport.hostLoadableLibs(QnnSoc.detect()))
            .forEach { line -> emit(diagFile, "运行库检查 $line") }
    }

    private fun emit(diagFile: File, line: String) {
        runCatching { diagFile.appendText("$line\n") }
        sendLog(line)
    }

    private fun sendProgress(percent: Int) {
        send(Message.obtain(null, QnnAsrProtocol.MSG_PROGRESS).apply { arg1 = percent })
    }

    private fun sendLog(line: String) {
        send(
            Message.obtain(null, QnnAsrProtocol.MSG_LOG).apply {
                data = Bundle().apply { putString(QnnAsrProtocol.KEY_LOG, line) }
            },
        )
    }

    private fun sendResult(path: String) {
        send(
            Message.obtain(null, QnnAsrProtocol.MSG_RESULT).apply {
                data = Bundle().apply { putString(QnnAsrProtocol.KEY_RESULT_PATH, path) }
            },
        )
    }

    private fun sendError(target: Messenger?, message: String) {
        if (target == null) return
        runCatching {
            target.send(
                Message.obtain(null, QnnAsrProtocol.MSG_ERROR).apply {
                    data = Bundle().apply { putString(QnnAsrProtocol.KEY_ERROR, message) }
                },
            )
        }
    }

    private fun sendPlain(what: Int) {
        send(Message.obtain(null, what))
    }

    private fun send(message: Message) {
        val target = reply ?: return
        try {
            target.send(message)
        } catch (e: RemoteException) {
            Log.w(TAG, "客户端已断开，取消 NPU 任务", e)
            engine?.cancel()
        }
    }

    private companion object {
        const val TAG = "QnnAsrService"
        val json = Json { ignoreUnknownKeys = true }
    }
}
