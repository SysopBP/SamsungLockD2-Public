#!/system/bin/sh
MODDIR=${0%/*}
[ "$(id -u)" = 0 ] || exit 1
umask 077
BASE=/data/adb/d2-system-debug
mkdir -p "$BASE" || exit 1
boot=$(cat /proc/sys/kernel/random/boot_id)
[ -n "$boot" ] || exit 1
LOCK="$BASE/crash-watch-lock"
# Verify process command line before treating an existing PID as our watcher.
if ! mkdir "$LOCK" 2>/dev/null; then
  old=$(cat "$LOCK/pid" 2>/dev/null)
  if [ "$(cat "$LOCK/boot" 2>/dev/null)" = "$boot" ] &&
     [ -n "$old" ] && kill -0 "$old" 2>/dev/null &&
     tr '\000' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q '/crash-watch.sh'; then
    exit 0
  fi
  rm -rf "$LOCK"; mkdir "$LOCK" || exit 1
fi
echo $$ > "$LOCK/pid"
echo "$boot" > "$LOCK/boot"
watch="$BASE/watch_$boot"
mkdir -p "$watch"
echo $$ > "$watch/watcher.pid"
# Three kernel-boot histories; a soft reboot shares the current history.
keep=0
for old in $(ls -dt "$BASE"/watch_* 2>/dev/null); do
  keep=$((keep + 1)); [ "$keep" -le 3 ] && continue
  [ "$old" = "$watch" ] || rm -rf "$old"
done
crash=''; events=''; d2=''; evidence=''
cleanup() {
  for pid in "$crash" "$events" "$d2"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
  done
  # A current forensic snapshot is bounded and may finish after disable.
  [ -n "$evidence" ] && wait "$evidence" 2>/dev/null
  rm -f "$watch/watcher.pid"
  rm -rf "$LOCK"
}
trap cleanup EXIT
trap 'exit 0' HUP INT TERM
start_readers() {
  if [ -z "$crash" ] || ! kill -0 "$crash" 2>/dev/null; then
    logcat -b crash -v threadtime -f "$watch/crashes.txt" -r 4096 -n 5 2> "$watch/crash-reader-errors.txt" &
    crash=$!
  fi
  if [ -z "$events" ] || ! kill -0 "$events" 2>/dev/null; then
    logcat -b events -v threadtime -f "$watch/crash-events.txt" -r 1024 -n 3 -s am_crash:I am_anr:I am_proc_died:I watchdog:I boot_progress_start:I boot_progress_system_run:I boot_progress_ams_ready:I boot_progress_enable_screen:I '*:S' 2> "$watch/event-reader-errors.txt" &
    events=$!
  fi
  if [ -z "$d2" ] || ! kill -0 "$d2" 2>/dev/null; then
    logcat -b main -b system -v threadtime -f "$watch/d2-events.txt" -r 2048 -n 3 -s SamsungLockD2:V D2XposedBridge:V D2SystemDebug:V AndroidRuntime:E Watchdog:V '*:S' 2> "$watch/d2-reader-errors.txt" &
    d2=$!
  fi
}
# Crash/event readers live until module disable/removal or reboot. Restart on
# logd disconnect, including soft reboot. No clearing or resizing log buffers.
tick=0
while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  start_readers
  if [ "$tick" -eq 0 ]; then
    if [ -z "$evidence" ] || ! kill -0 "$evidence" 2>/dev/null; then
      sh "$MODDIR/crash-evidence.sh" "$watch/evidence" &
      evidence=$!
    fi
  fi
  echo "ACTIVE $(date) uptime=$(cat /proc/uptime) crash_pid=$crash event_pid=$events d2_pid=$d2" > "$watch/status.txt"
  sleep 15
  tick=$(((tick + 1) % 20))
done
