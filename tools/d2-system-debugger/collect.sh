#!/system/bin/sh
MODDIR=${0%/*}
MODE=${1:-manual}
case "$MODE" in boot|manual) ;; *) exit 1;; esac
[ "$(id -u)" = 0 ] || { echo 'Root required.'; exit 1; }
umask 077
BASE=/data/adb/d2-system-debug
mkdir -p "$BASE" || exit 1
boot=$(cat /proc/sys/kernel/random/boot_id)
[ -n "$boot" ] || exit 1
# One collector of each kind per boot at a time; stale locks from older boots do not block.
LOCK="$BASE/lock_${MODE}_$boot"
if ! mkdir "$LOCK" 2>/dev/null; then
  old=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    echo "$MODE collection already running. Use Action later for the completed boot report."
    exit 0
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" || exit 1
fi
echo $$ > "$LOCK/pid"
reader=''
timer=''
early=''
cleanup() {
  [ -n "$timer" ] && kill "$timer" 2>/dev/null
  [ -n "$reader" ] && kill "$reader" 2>/dev/null
  [ -n "$early" ] && kill "$early" 2>/dev/null
  rm -rf "$LOCK"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
session="$BASE/session_$(date +%Y%m%d_%H%M%S)_${MODE}_${boot}_$$"
mkdir "$session" || exit 1
# Keep current plus two recent sessions; never delete active captures.
keep=0
for old in $(ls -dt "$BASE"/session_* 2>/dev/null); do
  keep=$((keep + 1))
  [ "$keep" -le 3 ] && continue
  active=$(cat "$old/collector.pid" 2>/dev/null)
  oldboot=$(cat "$old/boot-id.txt" 2>/dev/null)
  if [ "$oldboot" = "$boot" ] && [ -n "$active" ] && kill -0 "$active" 2>/dev/null; then continue; fi
  rm -rf "$old"
done
echo $$ > "$session/collector.pid"
echo "$boot" > "$session/boot-id.txt"
echo "STARTED mode=$MODE time=$(date) uptime=$(cat /proc/uptime)" > "$session/status.txt"
cat "$MODDIR/README.txt" > "$session/README.txt"
if [ "$MODE" = boot ]; then
  logcat -b all -v threadtime -f "$session/logcat.txt" -r 4096 -n 2 2> "$session/logcat-errors.txt" &
  reader=$!
  # Independent low-volume stream protects D2 startup from noisy all-buffer rotation.
  logcat -b main -b system -v threadtime -f "$session/early-d2.txt" -r 2048 -n 3 -s SamsungLockD2:V D2XposedBridge:V AndroidRuntime:E Watchdog:V '*:S' 2> "$session/early-d2-errors.txt" &
  early=$!
  # Independent cutoff bounds continuous recording even if snapshots time out.
  ( sleep 180; kill "$reader" "$early" 2>/dev/null ) &
  timer=$!
fi
logcat -b all -d -v threadtime -t 12000 2>&1 |
  grep -Ei 'SamsungLockD2|app\.d2lock|D2XposedBridge|GUARDIAN_|XPOSED|LSPosed|system_server|Watchdog|FATAL EXCEPTION|BOOT_COMPLETED|USER_UNLOCKED' |
  tail -4000 > "$session/early-events.txt"
if [ "$MODE" = boot ]; then sleep 20; else
  timeout 20 logcat -b all -d -v threadtime -t 12000 > "$session/logcat.txt" 2>&1
fi
sh "$MODDIR/snapshot.sh" > "$session/snapshot-start.txt" 2>&1
if [ "$MODE" = boot ]; then
  wait "$timer" 2>/dev/null
  timer=''
  wait "$reader" 2>/dev/null
  reader=''
  wait "$early" 2>/dev/null
  early=''
  sh "$MODDIR/snapshot.sh" > "$session/snapshot-end.txt" 2>&1
fi
sh "$MODDIR/crash-evidence.sh" "$session/crash-evidence"
timeout 6 dmesg 2>&1 | tail -2000 > "$session/kernel.txt"
for f in /sys/fs/pstore/*; do
  [ -f "$f" ] || continue
  tail -c 262144 "$f" > "$session/pstore-${f##*/}.txt" 2>&1
done
# Only known log directories, no module configuration, certificates or private keys.
count=0
for f in /data/adb/lspd/log/* /data/adb/lsposed/log/*; do
  [ -f "$f" ] || continue
  count=$((count + 1))
  [ "$count" -gt 8 ] && break
  tail -c 262144 "$f" > "$session/lsposed-$count.txt" 2>&1
done
for f in "$session"/logcat.txt* "$session"/early-d2.txt*; do
  [ -f "$f" ] || continue
  grep -Ei 'SamsungLockD2|app\.d2lock|D2XposedBridge|GUARDIAN_|XPOSED|LSPosed|system_server|Watchdog|FATAL EXCEPTION|ANR in|am_crash|am_anr|boot_progress|LockScreenActivity' "$f"
done | tail -8000 > "$session/focus-events.txt"
{
  echo 'D2 PATCH MARKERS (from retained logcat and LSPosed files)'
  for marker in GUARDIAN_MINIMAL_BOOT_READY_SENT GUARDIAN_WAKE_HOOK GUARDIAN_WAKE_CANDIDATE GUARDIAN_LOCKED_BOOT_RECEIVED GUARDIAN_USER_UNLOCKED_RECEIVED GUARDIAN_BOOT_PENDING_RESTORE GUARDIAN_SERVICE_REQUESTED GUARDIAN_XPOSED_REPLAY_CHECK GUARDIAN_SYS_EVENT_RECEIVED GUARDIAN_FOCUS_RESTORED GUARDIAN_HEALTHY; do
    echo "===== $marker ====="
    grep -h "$marker" "$session"/logcat.txt* "$session"/early-d2.txt* "$session"/early-events.txt "$session"/lsposed-*.txt 2>/dev/null | head -24
  done
  echo 'Use timestamps to check source=user_unlocked or boot_ready, postBoot=true then later false.'
  echo 'Focus restored / activity resumed alone does not prove the first visible frame.'
} > "$session/summary.txt"
echo "COMPLETE time=$(date) uptime=$(cat /proc/uptime)" >> "$session/status.txt"
rm -f "$session/collector.pid"
sh "$MODDIR/export.sh" "$session"
