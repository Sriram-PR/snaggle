#!/usr/bin/env sh
# Usage: scripts/spike.sh <init|probe|video|720|audio|cancel|sig|…> [url] [timeout_s] [yt-dlp params JSON]
# Triggers an action in the spike app and prints its log until the action finishes.
ACTION="$1"; URL="$2"; TIMEOUT="${3:-300}"; PARAMS="${4:-}"
# Same adb as Android Studio (the SDK copy); mixing versions restarts the server and drops the device.
adb() { "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" "$@"; }
LOG="$(mktemp "${TMPDIR:-/tmp}/spike.XXXXXX")"
adb logcat -c
adb logcat -v brief -s Spike:I Engine:D python.stderr:W python.stdout:I AndroidRuntime:E > "$LOG" &
LOGCAT=$!
if [ -n "$URL" ]; then
    adb shell am start --activity-single-top -n io.github.sriram_pr.snaggle/.DebugActivity --es spike_action "$ACTION" --es url "'$URL'" --es params "'$PARAMS'" >/dev/null 2>&1
else
    adb shell am start --activity-single-top -n io.github.sriram_pr.snaggle/.DebugActivity --es spike_action "$ACTION" >/dev/null 2>&1
fi
# The app logs "■ <label> took N ms" when an action ends.
end=$(( $(date +%s) + TIMEOUT ))
while ! grep -q '^I/Spike.*■ .* took ' "$LOG" && [ "$(date +%s)" -lt "$end" ]; do sleep 1; done
kill "$LOGCAT" 2>/dev/null
sed 's/^[A-Z]\/[^:]*: //' "$LOG" | grep -v '^--------- beginning'
rm -f "$LOG"
