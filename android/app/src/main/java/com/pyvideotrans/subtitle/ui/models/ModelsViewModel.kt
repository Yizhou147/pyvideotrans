package com.pyvideotrans.subtitle.ui.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyvideotrans.subtitle.data.model.LocalModel
import com.pyvideotrans.subtitle.data.model.ModelRepository
import com.pyvideotrans.subtitle.data.model.ModelSpec
import com.pyvideotrans.subtitle.util.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class DownloadState(
    val received: Long = 0L,
    val total: Long = 0L,
    val error: String? = null,
) {
    val fraction: Float?
        get() = if (total > 0L) (received.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else null
}

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ModelRepository(application)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _vadReady = MutableStateFlow(false)
    val vadReady: StateFlow<Boolean> = _vadReady.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importingVad = MutableStateFlow(false)
    val importingVad: StateFlow<Boolean> = _importingVad.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val manifest = repository.manifest()
    val modelRootPath: String = repository.modelRoot.absolutePath

    init {
        refresh()
    }

    fun refresh() {
        _models.value = repository.listAsrModels()
        _vadReady.value = repository.vadFile() != null
    }

    fun isDownloaded(spec: ModelSpec): Boolean = repository.isDownloaded(spec)

    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        _message.value = null
        jobs[spec.id] = viewModelScope.launch(Dispatchers.IO) {
            _downloads.update {
                it + (spec.id to DownloadState(received = 0L, total = spec.sizeBytes))
            }
            try {
                repository.download(spec) { received, total ->
                    _downloads.update {
                        it + (spec.id to DownloadState(received = received, total = total))
                    }
                }
                _downloads.update { it - spec.id }
                refresh()
            } catch (ce: CancellationException) {
                _downloads.update { it - spec.id }
                LogBus.warn("model", "已取消下载 ${spec.name}")
            } catch (t: Throwable) {
                _downloads.update {
                    it + (spec.id to DownloadState(
                        received = 0L,
                        total = spec.sizeBytes,
                        error = t.message ?: "下载失败",
                    ))
                }
                LogBus.error("model", "下载失败：${t.message}")
            } finally {
                jobs.remove(spec.id)
            }
        }
    }

    fun cancelDownload(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
        _downloads.update { it - spec.id }
    }

    fun delete(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
        _downloads.update { it - spec.id }
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(spec)
            refresh()
        }
    }

    fun deleteLocal(local: LocalModel) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(local)
            refresh()
        }
    }

    fun importModel(uri: Uri) {
        if (_importing.value || _importingVad.value) return
        _importing.value = true
        _message.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = repository.importAsr(uri)
                _message.value = "已导入自定义模型：${file.name}"
                refresh()
            } catch (t: Throwable) {
                _message.value = t.message ?: "导入失败"
                LogBus.error("model", "导入失败：${t.message}")
            } finally {
                _importing.value = false
            }
        }
    }

    fun importVad(uri: Uri) {
        if (_importing.value || _importingVad.value) return
        _importingVad.value = true
        _message.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = repository.importVad(uri)
                _message.value = "已导入 VAD 模型：${file.name}"
                refresh()
            } catch (t: Throwable) {
                _message.value = t.message ?: "VAD 导入失败"
                LogBus.error("model", "VAD 导入失败：${t.message}")
            } finally {
                _importingVad.value = false
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
