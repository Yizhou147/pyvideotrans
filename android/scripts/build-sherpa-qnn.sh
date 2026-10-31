#!/usr/bin/env bash
# Builds libsherpa-onnx-jni.so with the Qualcomm QNN backend and stages it,
# ONNX Runtime, and the QNN runtime libraries into app/src/main/jniLibs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY_DIR="$ROOT_DIR/third_party"
SHERPA_SRC="$THIRD_PARTY_DIR/sherpa-onnx"
BUILD_DIR="$SHERPA_SRC/build-android-arm64-v8a-qnn"
JNI_LIBS_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

QNN_VERSION="${QNN_VERSION:-2.40.0.251030}"
QNN_INCLUDE_DIR="${QNN_INCLUDE_DIR:-$THIRD_PARTY_DIR/qnn/include/qnn-include-$QNN_VERSION}"
QNN_LIBS_DIR="${QNN_LIBS_DIR:-$THIRD_PARTY_DIR/qnn/libs/qnn-libs-$QNN_VERSION}"
ORT_VERSION="${ORT_VERSION:-1.28.2}"
ORT_ROOT="${ORT_ROOT:-$THIRD_PARTY_DIR/onnxruntime/onnxruntime-android-$ORT_VERSION}"
# HTP architecture numbers of the supported SoCs:
#   SM8650 Snapdragon 8 Gen 3 -> v75
#   SM8750 Snapdragon 8 Elite -> v79
#   SM8850 Snapdragon 8 Elite Gen 5 -> v81
QNN_HTP_ARCHS="${QNN_HTP_ARCHS:-75 79 81}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

log() {
    printf '[sherpa-qnn] %s\n' "$*"
}

die() {
    printf '[sherpa-qnn] error: %s\n' "$*" >&2
    exit 1
}

[[ -f "$SHERPA_SRC/CMakeLists.txt" ]] || die "sherpa-onnx sources missing; run scripts/fetch-native-deps.sh first"
[[ -f "$QNN_INCLUDE_DIR/include/QNN/QnnInterface.h" ]] || die "QNN headers missing at $QNN_INCLUDE_DIR"
[[ -d "$QNN_LIBS_DIR" ]] || die "QNN runtime libraries missing at $QNN_LIBS_DIR"

if [[ -z "${ANDROID_NDK:-}" ]]; then
    for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/27.2.12479018"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            ANDROID_NDK="$candidate"
            break
        fi
    done
fi
[[ -n "${ANDROID_NDK:-}" && -d "$ANDROID_NDK" ]] || die "set ANDROID_NDK to the NDK 27 installation"

ort_lib_dir=""
for candidate in \
    "$ORT_ROOT/jni/arm64-v8a" \
    "$ORT_ROOT/onnxruntime-android-$ORT_VERSION/jni/arm64-v8a"; do
    if [[ -f "$candidate/libonnxruntime.so" ]]; then
        ort_lib_dir="$candidate"
        break
    fi
done
[[ -n "$ort_lib_dir" ]] || die "libonnxruntime.so not found under $ORT_ROOT"
ort_include_dir="$(dirname "$ort_lib_dir")/../headers"
[[ -f "$ort_include_dir/onnxruntime_cxx_api.h" ]] || die "ONNX Runtime headers missing at $ort_include_dir"

log "NDK: $ANDROID_NDK"
log "QNN headers: $QNN_INCLUDE_DIR"
log "QNN runtime: $QNN_LIBS_DIR"
log "ONNX Runtime: $ort_lib_dir"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

export QNN_SDK_ROOT="$QNN_INCLUDE_DIR"
export SHERPA_ONNXRUNTIME_LIB_DIR="$ort_lib_dir"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_include_dir"

cmake -S "$SHERPA_SRC" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_QNN=ON \
    -DSHERPA_ONNX_ENABLE_TTS=OFF \
    -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"

log "building sherpa-onnx-jni with $JOBS jobs"
cmake --build "$BUILD_DIR" --target sherpa-onnx-jni -j "$JOBS"

JNI_SO="$BUILD_DIR/lib/libsherpa-onnx-jni.so"
[[ -f "$JNI_SO" ]] || die "build finished but $JNI_SO is missing"

rm -rf "$JNI_LIBS_DIR"
mkdir -p "$JNI_LIBS_DIR"

cp "$JNI_SO" "$JNI_LIBS_DIR/"
cp "$ort_lib_dir/libonnxruntime.so" "$JNI_LIBS_DIR/"
if [[ -f "$ort_lib_dir/libc++_shared.so" ]]; then
    cp "$ort_lib_dir/libc++_shared.so" "$JNI_LIBS_DIR/"
fi

# libQnnHtp.so loads the per-SoC DSP skeleton from ADSP_LIBRARY_PATH at
# runtime. Only the HTP architectures of the target SoCs are packaged;
# libQnnHtpPrepare.so (context preparation, ~74 MB) is not needed because the
# models ship as precompiled context binaries.
qnn_common=(
    libQnnHtp.so
    libQnnSystem.so
    libQnnHtpNetRunExtensions.so
    libQnnHtpGraphFinalizeExtensions.so
    libQnnHtpPrepareExtensions.so
)
for name in "${qnn_common[@]}"; do
    if [[ -f "$QNN_LIBS_DIR/$name" ]]; then
        cp "$QNN_LIBS_DIR/$name" "$JNI_LIBS_DIR/"
    fi
done

for arch in $QNN_HTP_ARCHS; do
    skel="libQnnHtpV${arch}Skel.so"
    if [[ -f "$QNN_LIBS_DIR/$skel" ]]; then
        cp "$QNN_LIBS_DIR/$skel" "$JNI_LIBS_DIR/"
    else
        log "warning: $skel is not present in the QNN runtime package"
    fi
done

if [[ ! -f "$JNI_LIBS_DIR/libQnnHtp.so" ]]; then
    die "libQnnHtp.so was not staged; check the QNN runtime package layout"
fi

log "staged native libraries:"
ls -lh "$JNI_LIBS_DIR"

# The app sets android:extractNativeLibs="true" so these prebuilt libraries are
# available from nativeLibraryDir for dlopen and ADSP_LIBRARY_PATH.
for so in "$JNI_LIBS_DIR"/*.so; do
    align="$(readelf -lW "$so" 2>/dev/null | awk '/LOAD/{print $NF}' | sort -u | paste -sd, -)"
    log "p_align $(basename "$so"): $align"
done
