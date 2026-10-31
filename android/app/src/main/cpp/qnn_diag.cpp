// Diagnostic helpers used only by the dedicated NPU process.
//
// The QNN path aborts with _Exit() inside sherpa-onnx, so no Java exception is
// ever produced. These functions give the app a way to learn why: native stderr
// is redirected to a file that survives the process, and the QNN shared
// libraries are dlopen'ed explicitly so ld.so errors are reported by name.

#include <jni.h>

#include <dlfcn.h>
#include <cstdio>
#include <string>
#include <vector>

namespace {

std::string ToNativePath(JNIEnv *env, jstring value) {
    if (env == nullptr || value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

std::string SymbolCheck(void *handle, const char *symbol) {
    if (handle == nullptr) {
        return "";
    }
    dlerror();
    void *address = dlsym(handle, symbol);
    const char *error = dlerror();
    if (address != nullptr) {
        return std::string("符号 ") + symbol + " 可用";
    }
    return std::string("符号 ") + symbol + " 缺失: " + (error != nullptr ? error : "unknown");
}

// The entry points sherpa-onnx resolves right after dlopen(); a missing one is
// the difference between "wrong library" and "library the loader cannot use".
const char *RequiredSymbol(const std::string &name) {
    if (name == "libQnnHtp.so") {
        return "QnnInterface_getProviders";
    }
    if (name == "libQnnSystem.so") {
        return "QnnSystemInterface_getProviders";
    }
    return nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_pyvideotrans_subtitle_data_asr_QnnNativeProbe_nativeRedirectStderr(
        JNIEnv *env, jclass /*clazz*/, jstring path) {
    const std::string target = ToNativePath(env, path);
    if (target.empty()) {
        return JNI_FALSE;
    }
    if (freopen(target.c_str(), "a", stderr) == nullptr) {
        return JNI_FALSE;
    }
    // _Exit() skips buffering, so every line must reach the file immediately.
    setvbuf(stderr, nullptr, _IONBF, 0);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_pyvideotrans_subtitle_data_asr_QnnNativeProbe_nativeProbeLibraries(
        JNIEnv *env, jclass /*clazz*/, jobjectArray paths) {
    std::string report;
    if (paths == nullptr) {
        return env->NewStringUTF("");
    }
    const jsize count = env->GetArrayLength(paths);
    for (jsize i = 0; i < count; ++i) {
        auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const std::string path = ToNativePath(env, element);
        env->DeleteLocalRef(element);
        if (path.empty()) {
            continue;
        }
        const size_t slash = path.find_last_of('/');
        const std::string name =
                slash == std::string::npos ? path : path.substr(slash + 1);

        void *handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        report += name;
        if (handle == nullptr) {
            const char *error = dlerror();
            report += ": 加载失败 ";
            report += error != nullptr ? error : "未知错误";
        } else {
            report += ": 加载成功";
            const char *symbol = RequiredSymbol(name);
            if (symbol != nullptr) {
                report += " (" + SymbolCheck(handle, symbol) + ")";
            }
        }
        report += "\n";
        // Keep the handle open: QNN is about to load the same objects again and
        // closing here would only hide the failure that follows.
    }
    return env->NewStringUTF(report.c_str());
}

}  // extern "C"
