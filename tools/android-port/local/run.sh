#!/usr/bin/env bash
# AndroidOnly: WP-003 Serialized Linux snapshots with persistent Gradle caches.
set -euo pipefail
bundle="$1"
revision="$2"
key="$3"
tool="$4"
stages="$5"
overlay="$6"
mode="${7:-manual}"
queue_seconds="${8:-10}"
fast_seconds="${9:-180}"
completion="${10:-}"
token="${11:-}"
admission="${12:-}"
affected_plan="${13:-}"
affected_base="${14:-}"
work="${ANDROID_LOCAL_HOME:-$HOME/meshcoreone-work}"
mkdir -p "$work/local-checks"
py="${ANDROID_LOCAL_PYTHON:-$work/venv312/bin/python}"
[[ -x "$py" ]] || { echo "BLOCKED: provision pinned Python at $py" >&2; exit 1; }
if [[ "$mode" == "manual" || "$mode" == "advisory" ]]; then
  [[ -n "$completion" && -n "$token" && -f "$admission" ]] || {
    echo "BLOCKED: actual shared execution ownership and cleanup binding required" >&2; exit 1;
  }
  options=()
  [[ "$mode" != "manual" ]] || options+=(--preserve-daemons)
  exec "$py" "$tool/advisory.py" --completion "$completion" --token "$token" \
    --admission "$admission" --commit "$revision" "${options[@]}" -- \
    bash "$0" "$bundle" "$revision" "$key" "$tool" "$stages" "$overlay" "$mode-lane" \
    "$queue_seconds" "$fast_seconds" "$completion" "$token" "$admission" "$affected_plan" "$affected_base"
fi
[[ -f "$admission" && "$admission" -ef /proc/self/fd/8 ]] || {
  echo "BLOCKED: runner did not inherit actual shared execution admission" >&2; exit 1;
}
if [[ "$mode" == "owned-lock" ]]; then
  [[ "$work/local-checks/gradle.lock" -ef /proc/self/fd/9 ]] && flock -n 9 || {
    echo "BLOCKED: bounded runner did not inherit its actual lane lock" >&2; exit 1;
  }
else
  [[ "$mode" == "manual-lane" || "$mode" == "advisory-lane" ]] || {
    echo "BLOCKED: unknown local launcher mode: $mode" >&2; exit 1;
  }
  echo "Waiting for shared local Gradle lane (other workstreams may be running)..."
  exec 9>"$work/local-checks/gradle.lock"
  if [[ "$mode" == "advisory-lane" ]]; then
    if flock -E 75 -w "$queue_seconds" 9; then
      :
    else
      status=$?
      if [[ "$status" == 75 ]]; then
        echo "UNVERIFIED: shared local Gradle lane queued beyond ${queue_seconds}s" >&2
      else
        echo "BLOCKED: advisory lane acquisition failed with status $status" >&2
      fi
      exit "$status"
    fi
    exec "$py" "$tool/advisory.py" --seconds "$fast_seconds" -- \
      bash "$0" "$bundle" "$revision" "$key" "$tool" "$stages" "$overlay" owned-lock \
      "$queue_seconds" "$fast_seconds" "$completion" "$token" "$admission" "$affected_plan" "$affected_base"
  fi
  flock 9
fi
repo="$work/local-checks/repos/$key"
# Import from ext4 rather than repeatedly seeking through the Windows mount.
cp "$bundle" "$work/local-checks/input.bundle"
bundle="$work/local-checks/input.bundle"
if [[ ! -e "$repo" ]]; then
  git clone --no-hardlinks "$bundle" "$repo"
  mkdir "$repo/.git/meshcore-local-snapshot"
fi
[[ -d "$repo/.git/meshcore-local-snapshot" ]] || {
  echo "BLOCKED: refusing to overwrite an unmanaged snapshot directory: $repo" >&2
  exit 1
}
if ! git -C "$repo" cat-file -e "$revision^{commit}" 2>/dev/null; then
  echo "Importing new local Git objects..."
  git -C "$repo" fetch --quiet "$bundle" HEAD '+refs/*:refs/local-input/*'
fi
git -C "$repo" checkout --detach --force "$revision"
if [[ -n "$overlay" ]]; then
  "$py" - "$repo" "$overlay" <<'PY'
import json
from pathlib import Path
import sys
import tarfile
repo, overlay = map(Path, sys.argv[1:])
with tarfile.open(overlay / "working-tree.tar") as archive:
    archive.extractall(repo, filter="data")
for name in json.loads((overlay / "deleted.json").read_text(encoding="utf-8")):
    (repo / name).unlink()
PY
  # Match Git for Windows' clean conversion; CRLF checkout bytes must not become
  # synthetic changes to the read-only Swift pin.
  git -C "$repo" -c core.autocrlf=input add --all
  git -C "$repo" -c user.name=LocalChecks -c user.email=local-checks@localhost \
    -c core.hooksPath=/dev/null -c commit.gpgsign=false commit --allow-empty -m "Local working-tree snapshot"
fi
# Windows checkouts can contain CRLF. This changes only the generated Linux copy.
sed -i 's/\r$//' "$repo/android/gradlew"
chmod +x "$repo/android/gradlew"
output="$(mktemp -d "$work/local-checks/run-XXXXXXXX")"
echo "Local reports: $output"
options=()
[[ "$mode" != "owned-lock" ]] || options+=(--advisory)
if [[ -n "$affected_plan" || -n "$affected_base" ]]; then
  [[ -n "$affected_plan" && -n "$affected_base" && "$mode" == "manual-lane" ]] || {
    echo "BLOCKED: affected scope requires the exact manual owned request" >&2; exit 1;
  }
  options+=(--affected-plan "$affected_plan" --affected-base "$affected_base")
fi
"$py" "$tool/fast.py" --repo "$repo" --work "$work" --output "$output" --stages "$stages" "${options[@]}"
