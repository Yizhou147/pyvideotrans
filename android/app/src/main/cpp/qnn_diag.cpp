// Diagnostic helpers used only by the dedicated NPU process.
//
// The QNN path aborts with _Exit() inside sherpa-onnx, so no Java exception is
// ever produced. These functions give the app a way to learn why: native stderr
// is redirected to a file that survives the process, and the QNN shared
// libraries are dlopen'ed explicitly so ld.so errors are reported by name.

#include <jni.h>

#include <dlfcn.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
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

// Plain command-line binaries, tried in order. Exec'ing these runs them in the
// shell namespace, where /vendor/lib64 is searchable, unlike the app namespace
// that fails to resolve libcdsprpc.so by name. Each entry is an argv prefix
// without the target path; toybox dispatches by argument, ls by argv[0].
const char *const kLsCommands[][5] = {
        {"/system/bin/ls", "ls", "-l", nullptr, nullptr},
        {"/system/bin/toybox", "toybox", "ls", "-l", nullptr},
};

std::string RunAsShell(const char *const *command, const char *file) {
    // Built before fork(): the child must not allocate after the parent's locks
    // were copied.
    std::vector<char *> argv;
    for (const char *const *p = command; *p != nullptr; ++p) {
        argv.push_back(const_cast<char *>(*p));
    }
    argv.push_back(const_cast<char *>(file));
    argv.push_back(nullptr);

    int fds[2];
    if (pipe(fds) != 0) {
        return "(pipe 失败)";
    }
    const pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return "(fork 失败)";
    }
    if (pid == 0) {
        close(fds[0]);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        close(fds[1]);
        execv(argv[0], argv.data());
        _exit(127);
    }
    close(fds[1]);
    std::string output;
    char buffer[256];
    ssize_t read_bytes = 0;
    while ((read_bytes = read(fds[0], buffer, sizeof(buffer))) > 0) {
        output.append(buffer, static_cast<size_t>(read_bytes));
        if (output.size() > 1024) {
            break;
        }
    }
    close(fds[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    while (!output.empty() && (output.back() == '\n' || output.back() == '\r')) {
        output.pop_back();
    }
    if (output.empty()) {
        return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? "(无输出)" : "(执行失败)";
    }
    return output;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pyvideotrans_subtitle_data_asr_QnnNativeProbe_nativeSetEnv(
        JNIEnv *env, jclass /*clazz*/, jstring name, jstring value) {
    const std::string key = ToNativePath(env, name);
    const std::string val = ToNativePath(env, value);
    if (key.empty()) {
        return;
    }
    if (val.empty()) {
        unsetenv(key.c_str());
        return;
    }
    setenv(key.c_str(), val.c_str(), 1);
}

JNIEXPORT jstring JNICALL
Java_com_pyvideotrans_subtitle_data_asr_QnnNativeProbe_nativeProbeCdspRpc(
        JNIEnv *env, jclass /*clazz*/, jstring file) {
    const std::string target = ToNativePath(env, file);
    if (target.empty()) {
        return env->NewStringUTF("");
    }
    std::string report;
    for (const char *const *command : kLsCommands) {
        const std::string output = RunAsShell(command, target.c_str());
        report += std::string(command[0]) + ": " + output + "\n";
    }
    return env->NewStringUTF(report.c_str());
}

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
