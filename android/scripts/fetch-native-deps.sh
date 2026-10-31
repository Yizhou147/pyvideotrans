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

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY_DIR="$ROOT_DIR/third_party"

log() {
    printf '[native-deps] %s\n' "$*"
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
