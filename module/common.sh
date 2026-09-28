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

boot_completed() {
  [ "$(getprop sys.boot_completed)" = "1" ]
}

wait_for_android() {
  count=0
  while ! boot_completed && [ "$count" -lt 180 ]; do
    sleep 2
    count=$((count + 1))
  done
  boot_completed
}

package_installed() {
  /system/bin/pm path "$PKG" >/dev/null 2>&1
}

package_enabled() {
  package_installed || return 1
  /system/bin/dumpsys package "$PKG" 2>/dev/null | grep -q 'enabled=[01]'
}

start_companion() {
  # This optional module never participates in early boot. It only asks the
  # already-installed D2 app to start after Android reports boot complete.
  boot_completed || {
    log_line "start skipped: Android boot is not complete"
    return 1
  }
  package_enabled || {
    log_line "start skipped: D2 missing or disabled"
    return 1
  }
  /system/bin/am start-foreground-service -n "$SERVICE" >> "$LOG" 2>&1
}
