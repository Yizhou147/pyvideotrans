#!/usr/bin/env bash
# Fetches the pinned native dependencies used by the Android NDK build.
# The trees are intentionally not committed (see android/.gitignore).
set -euo pipefail

WHISPER_TAG="${WHISPER_TAG:-v1.9.5}"
WHISPER_COMMIT="${WHISPER_COMMIT:-d1be6fde11ac6e0407606b4e42fe72d34add8037}"
WHISPER_REPO="${WHISPER_REPO:-https://github.com/ggml-org/whisper.cpp.git}"

# The NDK sysroot ships vulkan.h but not the Vulkan-Hpp (vulkan.hpp) bindings
# required by ggml-vulkan. Pin the Khronos headers used by the target build.
VULKAN_HEADERS_TAG="${VULKAN_HEADERS_TAG:-vulkan-sdk-1.4.335.0}"
VULKAN_HEADERS_COMMIT="${VULKAN_HEADERS_COMMIT:-2fa203425eb4af9dfc6b03f97ef72b0b5bcb8350}"
VULKAN_HEADERS_REPO="${VULKAN_HEADERS_REPO:-https://github.com/KhronosGroup/Vulkan-Headers.git}"

# ggml-vulkan includes <spirv/unified1/spirv.hpp>; the NDK sysroot does not
# provide it either, so pin the Khronos SPIR-V headers to the same SDK tag.
SPIRV_HEADERS_TAG="${SPIRV_HEADERS_TAG:-vulkan-sdk-1.4.335.0}"
SPIRV_HEADERS_COMMIT="${SPIRV_HEADERS_COMMIT:-b824a462d4256d720bebb40e78b9eb8f78bbb305}"
SPIRV_HEADERS_REPO="${SPIRV_HEADERS_REPO:-https://github.com/KhronosGroup/SPIRV-Headers.git}"

# sherpa-onnx supplies the Qualcomm QNN backend used by the NPU engine. The
# qnnConfig field for Whisper only exists on master, so pin an exact commit.
SHERPA_COMMIT="${SHERPA_COMMIT:-935e5ded3d949a8b557ac44ef4dad03a7d2038e7}"
SHERPA_REPO="${SHERPA_REPO:-https://github.com/k2-fsa/sherpa-onnx.git}"

# Qualcomm QNN headers/runtime are republished by the sherpa-onnx project so a
# Qualcomm account is not needed to build. The runtime is redistributed under
# the Qualcomm AI Hub Model License; see assets/licenses.
QNN_VERSION="${QNN_VERSION:-2.40.0.251030}"
QNN_RELEASE_BASE="${QNN_RELEASE_BASE:-https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn}"
QNN_INCLUDE_SHA256="${QNN_INCLUDE_SHA256:-fafe1c9dc3f60393134f22f2c708a9ede287492fa20dedc22c9fabdcf5ebe70d}"
QNN_LIBS_SHA256="${QNN_LIBS_SHA256:-1078c0833b1e948518ff433e081eda511475c7fd9a82c7798a862f59b95a7ce5}"

# sherpa-onnx uses the ONNX Runtime Android build (CPU) for its VAD and
# non-QNN code paths.
ORT_VERSION="${ORT_VERSION:-1.28.2}"
ORT_URL="${ORT_URL:-https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip}"
ORT_SHA256="${ORT_SHA256:-01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY_DIR="$ROOT_DIR/third_party"

log() {
    printf '[native-deps] %s\n' "$*"
}

sha256_of() {
    sha256sum "$1" | awk '{print $1}'
}

download_artifact() {
    local name="$1" url="$2" dest="$3" sha="$4"
    if [[ -f "$dest" && "$(sha256_of "$dest")" == "$sha" ]]; then
        log "$name already downloaded and verified"
        return 0
    fi

    mkdir -p "$(dirname "$dest")"
    local tmp="$dest.part"
    rm -f "$tmp"
    log "downloading $name"
    curl -fL --retry 5 --retry-delay 5 --connect-timeout 30 --max-time 1800 \
        -o "$tmp" "$url"
    local actual
    actual="$(sha256_of "$tmp")"
    if [[ "$actual" != "$sha" ]]; then
        log "SHA-256 mismatch for $name: $actual (expected $sha)"
        rm -f "$tmp"
        exit 1
    fi
    mv "$tmp" "$dest"
    log "$name verified"
}

# fetch_commit <name> <repo> <commit> <dest-dir> <marker-file>
fetch_commit() {
    local name="$1" repo="$2" commit="$3" dest="$4" marker="$5"

    if [[ -f "$dest/$marker" && -f "$dest/CMakeLists.txt" ]]; then
        local current
        current="$(cat "$dest/$marker" 2>/dev/null || true)"
        if [[ "$current" == "$commit" ]]; then
            log "$name $commit already present"
            return 0
        fi
    fi

    mkdir -p "$THIRD_PARTY_DIR"
    local tmp
    tmp="$(mktemp -d "$THIRD_PARTY_DIR/.${name}-src.XXXXXX")"
    trap "rm -rf '$tmp'" EXIT

    log "fetching $repo at $commit"
    git init -q "$tmp"
    git -C "$tmp" remote add origin "$repo"
    git -C "$tmp" fetch -q --depth 1 origin "$commit"
    git -C "$tmp" checkout -q --detach FETCH_HEAD

    local actual
    actual="$(git -C "$tmp" rev-parse HEAD)"
    if [[ "$actual" != "$commit" ]]; then
        log "unexpected $name commit: $actual (expected $commit)"
        exit 1
    fi

    rm -rf "$tmp/.git"
    rm -rf "$dest"
    mv "$tmp" "$dest"
    printf '%s\n' "$commit" > "$dest/$marker"
    trap - EXIT
    log "$name ready at $dest"
}

# fetch_pinned <name> <repo> <tag> <commit> <dest-dir> <marker-file>
fetch_pinned() {
    local name="$1" repo="$2" tag="$3" commit="$4" dest="$5" marker="$6"

    if [[ -f "$dest/$marker" && -f "$dest/CMakeLists.txt" ]]; then
        local current
        current="$(cat "$dest/$marker" 2>/dev/null || true)"
        if [[ "$current" == "$commit" ]]; then
            log "$name $tag ($commit) already present"
            return 0
        fi
    fi

    mkdir -p "$THIRD_PARTY_DIR"
    local tmp
    tmp="$(mktemp -d "$THIRD_PARTY_DIR/.${name}-src.XXXXXX")"
    # shellcheck disable=SC2064
    trap "rm -rf '$tmp'" EXIT

    log "cloning $repo at $tag"
    git clone --depth 1 --single-branch --branch "$tag" "$repo" "$tmp"

    local actual
    actual="$(git -C "$tmp" rev-parse HEAD)"
    if [[ "$actual" != "$commit" ]]; then
        log "unexpected $name commit: $actual (expected $commit)"
        exit 1
    fi

    rm -rf "$tmp/.git"
    rm -rf "$dest"
    mv "$tmp" "$dest"
    printf '%s\n' "$commit" > "$dest/$marker"
    trap - EXIT
    log "$name $tag ready at $dest"
}

if ! command -v git >/dev/null 2>&1; then
    log "git is required"
    exit 1
fi

fetch_pinned "whisper.cpp" "$WHISPER_REPO" "$WHISPER_TAG" "$WHISPER_COMMIT" \
    "$THIRD_PARTY_DIR/whisper.cpp" ".pinned-commit"

fetch_pinned "Vulkan-Headers" "$VULKAN_HEADERS_REPO" "$VULKAN_HEADERS_TAG" "$VULKAN_HEADERS_COMMIT" \
    "$THIRD_PARTY_DIR/Vulkan-Headers" ".pinned-commit"

fetch_pinned "SPIRV-Headers" "$SPIRV_HEADERS_REPO" "$SPIRV_HEADERS_TAG" "$SPIRV_HEADERS_COMMIT" \
    "$THIRD_PARTY_DIR/SPIRV-Headers" ".pinned-commit"

fetch_commit "sherpa-onnx" "$SHERPA_REPO" "$SHERPA_COMMIT" \
    "$THIRD_PARTY_DIR/sherpa-onnx" ".pinned-commit"

QNN_DIR="$THIRD_PARTY_DIR/qnn"
download_artifact "QNN headers $QNN_VERSION" \
    "$QNN_RELEASE_BASE/qnn-include-$QNN_VERSION.tar.bz2" \
    "$QNN_DIR/dl/qnn-include-$QNN_VERSION.tar.bz2" "$QNN_INCLUDE_SHA256"
if [[ ! -f "$QNN_DIR/include/qnn-include-$QNN_VERSION/include/QNN/QnnInterface.h" ]]; then
    mkdir -p "$QNN_DIR/include"
    tar xjf "$QNN_DIR/dl/qnn-include-$QNN_VERSION.tar.bz2" -C "$QNN_DIR/include"
fi

download_artifact "QNN runtime $QNN_VERSION" \
    "$QNN_RELEASE_BASE/qnn-libs-$QNN_VERSION.tar.bz2" \
    "$QNN_DIR/dl/qnn-libs-$QNN_VERSION.tar.bz2" "$QNN_LIBS_SHA256"
if [[ ! -d "$QNN_DIR/libs/qnn-libs-$QNN_VERSION" ]]; then
    mkdir -p "$QNN_DIR/libs"
    tar xjf "$QNN_DIR/dl/qnn-libs-$QNN_VERSION.tar.bz2" -C "$QNN_DIR/libs"
fi

download_artifact "ONNX Runtime Android $ORT_VERSION" "$ORT_URL" \
    "$THIRD_PARTY_DIR/onnxruntime/dl/onnxruntime-android-$ORT_VERSION.zip" "$ORT_SHA256"
ORT_DIR="$THIRD_PARTY_DIR/onnxruntime/onnxruntime-android-$ORT_VERSION"
if [[ ! -f "$ORT_DIR/jni/arm64-v8a/libonnxruntime.so" ]]; then
    mkdir -p "$ORT_DIR"
    unzip -q -o "$THIRD_PARTY_DIR/onnxruntime/dl/onnxruntime-android-$ORT_VERSION.zip" \
        -d "$ORT_DIR"
fi
