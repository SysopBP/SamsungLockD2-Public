#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/crash-watch.sh" >/dev/null 2>&1 &
echo 'Recovering retained boot reports, then collecting current state...'
for session in /data/adb/d2-system-debug/session_*; do
  [ -d "$session" ] || continue
  sh "$MODDIR/export.sh" "$session"
done
sh "$MODDIR/collect.sh" manual
