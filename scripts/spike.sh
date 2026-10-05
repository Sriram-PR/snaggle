#!/usr/bin/env sh
# Usage: scripts/spike.sh <init|probe|video|720|audio|cancel> [url] [timeout_s]
# Triggers an action in the spike app and prints its log until the action finishes.
ACTION="$1"; URL="$2"; TIMEOUT="${3:-300}"
LOG="$(mktemp "${TMPDIR:-/tmp}/spike.XXXXXX")"
adb logcat -c
adb logcat -v brief -s Spike:I Engine:D python.stderr:W python.stdout:I AndroidRuntime:E > "$LOG" &
LOGCAT=$!
if [ -n "$URL" ]; then
    adb shell am start --activity-single-top -n dev.placeholder.app/.MainActivity --es spike_action "$ACTION" --es url "'$URL'" >/dev/null 2>&1
else
    adb shell am start --activity-single-top -n dev.placeholder.app/.MainActivity --es spike_action "$ACTION" >/dev/null 2>&1
fi
# The app logs "■ <label> took N ms" when an action ends.
end=$(( $(date +%s) + TIMEOUT ))
while ! grep -q '^I/Spike.*■ .* took ' "$LOG" && [ "$(date +%s)" -lt "$end" ]; do sleep 1; done
kill "$LOGCAT" 2>/dev/null
sed 's/^[A-Z]\/[^:]*: //' "$LOG" | grep -v '^--------- beginning'
rm -f "$LOG"
