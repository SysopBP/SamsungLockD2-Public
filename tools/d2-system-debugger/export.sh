#!/system/bin/sh
umask 077
session=$1
case "$session" in /data/adb/d2-system-debug/session_*) ;; *) exit 1;; esac
[ -d "$session" ] || exit 1
name="D2_Debug_${session##*/}"
# Separate exports can run safely while the boot logger is writing.
work=$(mktemp -d /data/local/tmp/d2export.XXXXXX) || exit 1
trap 'rm -rf "$work"' EXIT
trap 'exit 1' HUP INT TERM
# Freeze a best-effort copy before archiving so rotating/live files cannot
# invalidate the tar. Mark live copies explicitly; use Action for a fresh copy.
mkdir "$work/tree" || exit 1
cp -R "$session/." "$work/tree/" 2> "$work/copy-errors.txt"
echo "Export snapshot: $(date). Live streams can change during copy." > "$work/tree/export-status.txt"
boot=$(cat "$session/boot-id.txt" 2>/dev/null)
case "$boot" in *[!a-f0-9-]*|'') boot=invalid;; esac
mkdir "$work/tree/crash-history"
for history in /data/adb/d2-system-debug/watch_*; do
  [ -d "$history" ] || continue
  # Manual exports recover all three retained boot histories. Boot exports
  # include only their own kernel boot history.
  case "${session##*/}" in
    *_manual_*) ;;
    *) [ "$history" = "/data/adb/d2-system-debug/watch_$boot" ] || continue;;
  esac
  cp -R "$history" "$work/tree/crash-history/" 2>> "$work/copy-errors.txt"
done
cat "$work/copy-errors.txt" >> "$work/tree/export-status.txt"
if ! tar -cf "$work/report.tar" -C "$work/tree" .; then
  echo "Archive failed. Retained: $session"
  exit 1
fi
if ! gzip -c "$work/report.tar" > "$work/report.tar.gz"; then exit 1; fi
for dir in /storage/emulated/0/Download /sdcard/Download; do
  [ -d "$dir" ] || continue
  target="$dir/$name.tar.gz"
  if cat "$work/report.tar.gz" > "$target.part" 2>/dev/null &&
     [ -s "$target.part" ] && mv -f "$target.part" "$target"; then
    chmod 0644 "$target" 2>/dev/null
    echo "Saved: $target"
    log -t D2SystemDebug "Saved: $target"
    exit 0
  fi
done
echo "Downloads unavailable; retained: $session. Use Action after unlocking."
log -t D2SystemDebug "Export deferred: $session"
exit 1
