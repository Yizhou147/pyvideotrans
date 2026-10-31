#!/usr/bin/env bash
# Fetches the pinned whisper.cpp sources used by the Android NDK build.
# The tree is intentionally not committed (see android/.gitignore).
set -euo pipefail

WHISPER_TAG="${WHISPER_TAG:-v1.9.5}"
WHISPER_COMMIT="${WHISPER_COMMIT:-d1be6fde11ac6e0407606b4e42fe72d34add8037}"
WHISPER_REPO="${WHISPER_REPO:-https://github.com/ggml-org/whisper.cpp.git}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT_DIR/third_party/whisper.cpp"
STAMP_FILE="$DEST_DIR/.pinned-commit"

log() {
    printf '[native-deps] %s\n' "$*"
}

if [[ -f "$STAMP_FILE" && -f "$DEST_DIR/CMakeLists.txt" ]]; then
    CURRENT_COMMIT="$(cat "$STAMP_FILE" 2>/dev/null || true)"
    if [[ "$CURRENT_COMMIT" == "$WHISPER_COMMIT" ]]; then
        log "whisper.cpp $WHISPER_TAG ($WHISPER_COMMIT) already present"
        exit 0
    fi
fi

if ! command -v git >/dev/null 2>&1; then
    log "git is required"
    exit 1
fi

mkdir -p "$ROOT_DIR/third_party"
TMP_DIR="$(mktemp -d "$ROOT_DIR/third_party/.whisper-src.XXXXXX")"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

log "cloning $WHISPER_REPO at $WHISPER_TAG"
git clone --depth 1 --single-branch --branch "$WHISPER_TAG" "$WHISPER_REPO" "$TMP_DIR"

ACTUAL_COMMIT="$(git -C "$TMP_DIR" rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$WHISPER_COMMIT" ]]; then
    log "unexpected whisper.cpp commit: $ACTUAL_COMMIT (expected $WHISPER_COMMIT)"
    exit 1
fi

rm -rf "$TMP_DIR/.git"
rm -rf "$DEST_DIR"
mv "$TMP_DIR" "$DEST_DIR"
printf '%s\n' "$WHISPER_COMMIT" > "$STAMP_FILE"
log "whisper.cpp $WHISPER_TAG ready at $DEST_DIR"
