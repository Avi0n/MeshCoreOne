#!/usr/bin/env bash
# Cloud Agent install: provision the Linux-runnable developer tooling for MeshCore One.
#
# MeshCore One is an Apple-platform (iOS/iPadOS/macOS) app. Building the app, running
# the XCTest/Swift Testing suites, and regenerating the Xcode project all require macOS
# + Xcode 27, which a Linux Cloud Agent cannot provide. The two checks that DO run on
# Linux are the SwiftLint and SwiftFormat gates from .github/workflows/lint.yml, so this
# script installs those two tools (pinned to match CI) and nothing else.
#
# Idempotent: re-running is a no-op when the pinned versions are already installed.
set -euo pipefail

SWIFTLINT_VERSION="0.65.1"
# Pinned to match .github/workflows/lint.yml (SWIFTFORMAT_VERSION: 0.63.0).
SWIFTFORMAT_VERSION="0.63.0"

BIN_DIR="/usr/local/bin"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

log() { printf '==> %s\n' "$*"; }

# Prefer sudo when not already root so the script works under either user.
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

install_swiftlint() {
  if command -v swiftlint >/dev/null 2>&1 && [ "$(swiftlint version 2>/dev/null)" = "$SWIFTLINT_VERSION" ]; then
    log "swiftlint $SWIFTLINT_VERSION already installed"
    return
  fi
  log "installing swiftlint $SWIFTLINT_VERSION (linux, static)"
  curl -fsSL --retry 4 --retry-delay 4 -o "$TMP_DIR/swiftlint.zip" \
    "https://github.com/realm/SwiftLint/releases/download/${SWIFTLINT_VERSION}/swiftlint_linux_amd64.zip"
  unzip -o -q "$TMP_DIR/swiftlint.zip" -d "$TMP_DIR/swiftlint"
  # swiftlint-static bundles SourceKit, so it needs no Swift toolchain on the VM.
  $SUDO install -m 0755 "$TMP_DIR/swiftlint/swiftlint-static" "$BIN_DIR/swiftlint"
  installed="$(swiftlint version)"
  [ "$installed" = "$SWIFTLINT_VERSION" ] || { echo "swiftlint version mismatch: $installed" >&2; exit 1; }
}

install_swiftformat() {
  if command -v swiftformat >/dev/null 2>&1 && [ "$(swiftformat --version 2>/dev/null)" = "$SWIFTFORMAT_VERSION" ]; then
    log "swiftformat $SWIFTFORMAT_VERSION already installed"
    return
  fi
  log "installing swiftformat $SWIFTFORMAT_VERSION (linux)"
  curl -fsSL --retry 4 --retry-delay 4 -o "$TMP_DIR/swiftformat.zip" \
    "https://github.com/nicklockwood/SwiftFormat/releases/download/${SWIFTFORMAT_VERSION}/swiftformat_linux.zip"
  unzip -o -q "$TMP_DIR/swiftformat.zip" -d "$TMP_DIR/swiftformat"
  $SUDO install -m 0755 "$TMP_DIR/swiftformat/swiftformat_linux" "$BIN_DIR/swiftformat"
  installed="$(swiftformat --version)"
  [ "$installed" = "$SWIFTFORMAT_VERSION" ] || { echo "swiftformat version mismatch: $installed" >&2; exit 1; }
}

install_swiftlint
install_swiftformat

log "tooling ready: swiftlint $(swiftlint version), swiftformat $(swiftformat --version)"
