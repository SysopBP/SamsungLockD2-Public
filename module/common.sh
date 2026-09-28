#!/system/bin/sh

SCRIPT_DIR=${0%/*}
MODDIR=${MODPATH:-$SCRIPT_DIR}
PKG="app.d2lock"
ACTIVITY="$PKG/.MainActivity"
SERVICE="$PKG/.lockscreen.LockScreenService"
LOG="$MODDIR/logs/module.log"

log_line() {
  mkdir -p "$MODDIR/logs"
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
}

wait_for_android() {
  count=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$count" -lt 180 ]; do
    sleep 2
    count=$((count + 1))
  done
  [ "$(getprop sys.boot_completed)" = "1" ]
}

package_installed() {
  /system/bin/pm path "$PKG" >/dev/null 2>&1
}

start_companion() {
  /system/bin/am start-foreground-service -n "$SERVICE" >> "$LOG" 2>&1
}
