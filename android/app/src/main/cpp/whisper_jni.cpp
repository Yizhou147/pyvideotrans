#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml-backend.h"

#define JNI_TAG "whisper-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, JNI_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, JNI_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JNI_TAG, __VA_ARGS__)

namespace {

struct WhisperHolder {
    whisper_context * ctx = nullptr;
    std::atomic<bool> aborted{false};
};

JavaVM * g_vm = nullptr;
std::mutex g_sink_mutex;
jobject g_log_sink = nullptr;
jmethodID g_log_method = nullptr;

JNIEnv * acquireEnv(JavaVM * vm, bool & attached) {
    attached = false;
    JNIEnv * env = nullptr;
    if (vm == nullptr) {
        return nullptr;
    }
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        attached = true;
    }
    return env;
}

void nativeLogCallback(ggml_log_level level, const char * text, void * /*user_data*/) {
    int priority = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_print(priority, JNI_TAG, "%s", text != nullptr ? text : "");

    std::lock_guard<std::mutex> lock(g_sink_mutex);
    if (g_log_sink == nullptr || g_log_method == nullptr || g_vm == nullptr) {
        return;
    }
    bool attached = false;
    JNIEnv * env = acquireEnv(g_vm, attached);
    if (env == nullptr) {
        return;
    }
    jstring message = env->NewStringUTF(text != nullptr ? text : "");
    if (message != nullptr) {
        env->CallVoidMethod(g_log_sink, g_log_method, static_cast<jint>(level), message);
        env->DeleteLocalRef(message);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

struct ProgressBridge {
    JavaVM * vm = nullptr;
    jobject sink = nullptr;
    jmethodID method = nullptr;
};

void progressCallback(
        whisper_context * /*ctx*/,
        whisper_state * /*state*/,
        int progress,
        void * user_data) {
    auto * bridge = static_cast<ProgressBridge *>(user_data);
    if (bridge == nullptr || bridge->sink == nullptr || bridge->method == nullptr) {
        return;
    }
    bool attached = false;
    JNIEnv * env = acquireEnv(bridge->vm, attached);
    if (env == nullptr) {
        return;
    }
    env->CallVoidMethod(bridge->sink, bridge->method, static_cast<jint>(progress));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (attached) {
        bridge->vm->DetachCurrentThread();
    }
}

bool abortCallback(void * user_data) {
    auto * holder = static_cast<WhisperHolder *>(user_data);
    return holder != nullptr && holder->aborted.load(std::memory_order_relaxed);
}

std::string jstringToUtf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwIllegalState(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

uint16_t readU16(const uint8_t * p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

uint32_t readU32(const uint8_t * p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

struct WavData {
    std::vector<float> samples;
    int sampleRate = 0;
};

bool readWavFile(const std::string & path, WavData & out, std::string & error) {
    FILE * file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) {
        error = "无法打开音频文件：" + path;
        return false;
    }

    auto fail = [&](const char * message) -> bool {
        std::fclose(file);
        error = message;
        return false;
    };

    char riff[4] = {};
    char wave[4] = {};
    uint32_t riffSize = 0;
    if (std::fread(riff, 1, 4, file) != 4 ||
        std::fread(&riffSize, 4, 1, file) != 1 ||
        std::fread(wave, 1, 4, file) != 4) {
        return fail("WAV 文件头不完整");
    }
    if (std::memcmp(riff, "RIFF", 4) != 0 || std::memcmp(wave, "WAVE", 4) != 0) {
        return fail("不是有效的 RIFF/WAVE 文件");
    }
    (void) riffSize;

    bool haveFmt = false;
    bool haveData = false;
    uint16_t audioFormat = 0;
    uint16_t channels = 0;
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;
    std::vector<int16_t> pcm;

    while (true) {
        char id[4] = {};
        uint32_t chunkSize = 0;
        if (std::fread(id, 1, 4, file) != 4) {
            break;
        }
        if (std::fread(&chunkSize, 4, 1, file) != 1) {
            return fail("WAV 分块头不完整");
        }

        if (std::memcmp(id, "fmt ", 4) == 0) {
            uint8_t fmt[40] = {};
            const size_t toRead = std::min<size_t>(chunkSize, sizeof(fmt));
            if (std::fread(fmt, 1, toRead, file) != toRead) {
                return fail("WAV fmt 分块读取失败");
            }
            if (chunkSize > toRead) {
                std::fseek(file, static_cast<long>(chunkSize - toRead), SEEK_CUR);
            }
            if (chunkSize & 1u) {
                std::fseek(file, 1, SEEK_CUR);
            }
            if (toRead < 16) {
                return fail("WAV fmt 分块过短");
            }
            audioFormat = readU16(fmt);
            channels = readU16(fmt + 2);
            sampleRate = readU32(fmt + 4);
            bitsPerSample = readU16(fmt + 14);
            haveFmt = true;
        } else if (std::memcmp(id, "data", 4) == 0) {
            if (chunkSize == 0 || chunkSize > 2ULL * 1024ULL * 1024ULL * 1024ULL) {
                return fail("WAV 音频数据大小异常");
            }
            pcm.resize(chunkSize / 2);
            const size_t framesRead = std::fread(pcm.data(), 2, pcm.size(), file);
            if (framesRead != pcm.size()) {
                return fail("WAV 音频数据不完整");
            }
            if (chunkSize & 1u) {
                std::fseek(file, 1, SEEK_CUR);
            }
            haveData = true;
        } else {
            std::fseek(file, static_cast<long>(chunkSize + (chunkSize & 1u)), SEEK_CUR);
        }
    }

    std::fclose(file);

    if (!haveFmt || !haveData) {
        error = "WAV 缺少 fmt 或 data 分块";
        return false;
    }
    if (audioFormat != 1) {
        error = "仅支持 PCM WAV 音频";
        return false;
    }
    if (bitsPerSample != 16) {
        error = "仅支持 16-bit PCM WAV 音频";
        return false;
    }
    if (channels == 0) {
        error = "WAV 声道数无效";
        return false;
    }
    if (sampleRate != WHISPER_SAMPLE_RATE) {
        error = "音频采样率必须为 16000Hz";
        return false;
    }
    if (pcm.empty()) {
        error = "WAV 不包含采样数据";
        return false;
    }

    const size_t sampleCount = pcm.size() / channels;
    out.samples.resize(sampleCount);
    for (size_t i = 0; i < sampleCount; ++i) {
        float sum = 0.0f;
        for (size_t c = 0; c < channels; ++c) {
            sum += static_cast<float>(pcm[i * channels + c]);
        }
        out.samples[i] = sum / (static_cast<float>(channels) * 32768.0f);
    }
    out.sampleRate = static_cast<int>(sampleRate);
    return true;
}

void appendJsonEscaped(std::string & out, const char * text) {
    if (text == nullptr) {
        return;
    }
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text); *p != 0; ++p) {
        const unsigned char c = *p;
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buffer[7] = {};
                    std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                    out += buffer;
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * /*reserved*/) {
    g_vm = vm;
    whisper_log_set(nativeLogCallback, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeSetLogSink(JNIEnv * env, jclass, jobject sink) {
    std::lock_guard<std::mutex> lock(g_sink_mutex);
    if (g_log_sink != nullptr) {
        env->DeleteGlobalRef(g_log_sink);
        g_log_sink = nullptr;
        g_log_method = nullptr;
    }
    if (sink == nullptr) {
        return;
    }
    jclass cls = env->GetObjectClass(sink);
    if (cls == nullptr) {
        return;
    }
    jmethodID method = env->GetMethodID(cls, "log", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (method == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }
    g_log_sink = env->NewGlobalRef(sink);
    g_log_method = method;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeSystemInfo(JNIEnv * env, jclass) {
    try {
        const char * info = whisper_print_system_info();
        return env->NewStringUTF(info != nullptr ? info : "");
    } catch (const std::exception & e) {
        return env->NewStringUTF((std::string("读取系统信息失败: ") + e.what()).c_str());
    } catch (...) {
        return env->NewStringUTF("读取系统信息失败");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeDevices(JNIEnv * env, jclass) {
    std::string out;
    try {
        const size_t count = ggml_backend_dev_count();
        if (count == 0) {
            out = "没有已注册的 ggml 后端设备";
        }
        for (size_t i = 0; i < count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev == nullptr) {
                continue;
            }
            const char * type = "unknown";
            switch (ggml_backend_dev_type(dev)) {
                case GGML_BACKEND_DEVICE_TYPE_CPU:   type = "CPU";   break;
                case GGML_BACKEND_DEVICE_TYPE_GPU:   type = "GPU";   break;
                case GGML_BACKEND_DEVICE_TYPE_IGPU:  type = "IGPU";  break;
                case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "ACCEL"; break;
                case GGML_BACKEND_DEVICE_TYPE_META:  type = "META";  break;
                default: break;
            }
            const char * name = ggml_backend_dev_name(dev);
            const char * desc = ggml_backend_dev_description(dev);
            out += "[";
            out += std::to_string(i);
            out += "] ";
            out += type;
            out += " ";
            out += name != nullptr ? name : "?";
            if (desc != nullptr && desc[0] != '\0') {
                out += " — ";
                out += desc;
            }
            out += "\n";
        }
        while (!out.empty() && out.back() == '\n') {
            out.pop_back();
        }
    } catch (const std::exception & e) {
        out = std::string("枚举 ggml 设备失败: ") + e.what();
    } catch (...) {
        out = "枚举 ggml 设备失败";
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeGpuBackend(JNIEnv * env, jclass) {
    std::string out;
    try {
        const size_t count = ggml_backend_dev_count();
        for (size_t i = 0; i < count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev == nullptr) {
                continue;
            }
            const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
            if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) {
                continue;
            }
            const char * name = ggml_backend_dev_name(dev);
            const char * desc = ggml_backend_dev_description(dev);
            out = name != nullptr ? name : "GPU";
            if (desc != nullptr && desc[0] != '\0') {
                out += " — ";
                out += desc;
            }
            break;
        }
    } catch (...) {
        out.clear();
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeInit(
        JNIEnv * env, jclass, jstring modelPath, jboolean useGpu) {
    if (modelPath == nullptr) {
        throwIllegalState(env, "模型路径为空");
        return 0;
    }
    const std::string model = jstringToUtf8(env, modelPath);
    if (model.empty()) {
        throwIllegalState(env, "模型路径无法解析");
        return 0;
    }

    try {
        whisper_context_params params = whisper_context_default_params();
        params.use_gpu = useGpu == JNI_TRUE;
        params.flash_attn = false;

        LOGI("loading model: %s (gpu=%d)", model.c_str(), params.use_gpu ? 1 : 0);
        whisper_context * ctx = whisper_init_from_file_with_params(model.c_str(), params);
        if (ctx == nullptr) {
            LOGE("whisper_init_from_file_with_params failed");
            return 0;
        }
        auto * holder = new WhisperHolder();
        holder->ctx = ctx;
        LOGI("model loaded");
        return reinterpret_cast<jlong>(holder);
    } catch (const std::exception & e) {
        LOGE("nativeInit exception: %s", e.what());
        throwIllegalState(env, std::string("加载模型异常：") + e.what());
        return 0;
    } catch (...) {
        throwIllegalState(env, "加载模型发生未知异常");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeTranscribe(
        JNIEnv * env,
        jclass,
        jlong contextPtr,
        jstring wavPath,
        jstring vadPath,
        jstring language,
        jint threads,
        jboolean noContext,
        jboolean tokenTimestamps,
        jstring initialPrompt,
        jobject progressSink) {
    auto * holder = reinterpret_cast<WhisperHolder *>(contextPtr);
    if (holder == nullptr || holder->ctx == nullptr) {
        throwIllegalState(env, "Whisper 上下文无效");
        return nullptr;
    }
    if (wavPath == nullptr) {
        throwIllegalState(env, "音频路径为空");
        return nullptr;
    }

    const std::string wavFile = jstringToUtf8(env, wavPath);
    const std::string vadFile = jstringToUtf8(env, vadPath);
    const std::string languageValue = jstringToUtf8(env, language);
    const std::string promptValue = jstringToUtf8(env, initialPrompt);

    WavData audio;
    std::string wavError;
    bool wavOk = false;
    try {
        wavOk = readWavFile(wavFile, audio, wavError);
    } catch (const std::exception & e) {
        throwIllegalState(env, std::string("读取音频异常：") + e.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "读取音频发生未知异常");
        return nullptr;
    }
    if (!wavOk) {
        throwIllegalState(env, wavError);
        return nullptr;
    }
    if (audio.samples.size() > static_cast<size_t>(1ULL << 31)) {
        throwIllegalState(env, "音频过长，超出 Whisper 单次处理限制");
        return nullptr;
    }

    ProgressBridge bridge;
    jobject progressGlobal = nullptr;
    if (progressSink != nullptr) {
        bridge.vm = g_vm;
        bridge.sink = env->NewGlobalRef(progressSink);
        progressGlobal = bridge.sink;
        jclass progressClass = env->GetObjectClass(progressSink);
        bridge.method = env->GetMethodID(progressClass, "onProgress", "(I)V");
        env->DeleteLocalRef(progressClass);
        if (bridge.method == nullptr) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            if (progressGlobal != nullptr) {
                env->DeleteGlobalRef(progressGlobal);
            }
            throwIllegalState(env, "进度回调接口无效");
            return nullptr;
        }
    }

    try {
        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.no_context = noContext == JNI_TRUE;
        params.token_timestamps = tokenTimestamps == JNI_TRUE;
        params.single_segment = false;
        params.n_threads = std::max(1, std::min(static_cast<int>(threads), 16));
        params.temperature = 0.0f;
        params.temperature_inc = 0.2f;
        params.no_speech_thold = 0.6f;
        params.suppress_blank = true;
        params.max_len = 0;
        params.detect_language = false;
        params.language =
            (languageValue.empty() || languageValue == "auto") ? nullptr : languageValue.c_str();
        if (!promptValue.empty()) {
            params.initial_prompt = promptValue.c_str();
        }
        if (!vadFile.empty()) {
            params.vad = true;
            params.vad_model_path = vadFile.c_str();
            params.vad_params = whisper_vad_default_params();
        }
        if (bridge.sink != nullptr) {
            params.progress_callback = progressCallback;
            params.progress_callback_user_data = &bridge;
        }
        params.abort_callback = abortCallback;
        params.abort_callback_user_data = holder;

        holder->aborted.store(false, std::memory_order_relaxed);
        const int64_t audioSamples = static_cast<int64_t>(audio.samples.size());
        LOGI(
            "transcribe start: samples=%lld rate=%d threads=%d vad=%d language=%s",
            static_cast<long long>(audioSamples),
            audio.sampleRate,
            params.n_threads,
            params.vad ? 1 : 0,
            params.language != nullptr ? params.language : "auto");

        const int result = whisper_full(
            holder->ctx,
            params,
            audio.samples.data(),
            static_cast<int>(audio.samples.size()));

        if (progressGlobal != nullptr) {
            env->DeleteGlobalRef(progressGlobal);
            progressGlobal = nullptr;
        }

        if (result != 0) {
            if (holder->aborted.load(std::memory_order_relaxed)) {
                throwIllegalState(env, "识别已取消");
            } else {
                throwIllegalState(env, "Whisper 识别失败，返回码 " + std::to_string(result));
            }
            return nullptr;
        }

        std::string json;
        json.reserve(4096);
        const int languageId = whisper_full_lang_id(holder->ctx);
        const char * detectedLanguage = languageId >= 0 ? whisper_lang_str(languageId) : nullptr;
        json += "{\"language\":\"";
        appendJsonEscaped(json, detectedLanguage != nullptr ? detectedLanguage : "");
        json += "\",\"audio_ms\":";
        json += std::to_string(audioSamples * 1000 / WHISPER_SAMPLE_RATE);
        json += ",\"segments\":[";

        const int segmentCount = whisper_full_n_segments(holder->ctx);
        for (int i = 0; i < segmentCount; ++i) {
            if (i > 0) {
                json += ',';
            }
            const int64_t t0 = whisper_full_get_segment_t0(holder->ctx, i);
            const int64_t t1 = whisper_full_get_segment_t1(holder->ctx, i);
            const char * text = whisper_full_get_segment_text(holder->ctx, i);
            json += "{\"t0\":";
            json += std::to_string(t0 * 10);
            json += ",\"t1\":";
            json += std::to_string(t1 * 10);
            json += ",\"text\":\"";
            appendJsonEscaped(json, text);
            json += "\"}";
        }
        json += "]}";
        LOGI("transcribe done: %d segments", segmentCount);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception & e) {
        if (progressGlobal != nullptr) {
            env->DeleteGlobalRef(progressGlobal);
        }
        LOGE("nativeTranscribe exception: %s", e.what());
        throwIllegalState(env, std::string("识别异常：") + e.what());
        return nullptr;
    } catch (...) {
        if (progressGlobal != nullptr) {
            env->DeleteGlobalRef(progressGlobal);
        }
        throwIllegalState(env, "识别发生未知异常");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeAbort(
        JNIEnv * /*env*/, jclass, jlong contextPtr) {
    auto * holder = reinterpret_cast<WhisperHolder *>(contextPtr);
    if (holder != nullptr) {
        holder->aborted.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pyvideotrans_subtitle_data_asr_WhisperNative_nativeFree(
        JNIEnv * /*env*/, jclass, jlong contextPtr) {
    auto * holder = reinterpret_cast<WhisperHolder *>(contextPtr);
    if (holder == nullptr) {
        return;
    }
    if (holder->ctx != nullptr) {
        whisper_free(holder->ctx);
        holder->ctx = nullptr;
    }
    delete holder;
}
