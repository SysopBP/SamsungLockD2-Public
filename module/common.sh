#!/system/bin/sh

# Resolve the real module directory. KernelSU can invoke action/service scripts
# with $0 pointing at a temporary/extracted path, so prefer KSU's MODPATH when
# available and fall back to the installed module path.
SCRIPT_DIR=${0%/*}
MODDIR=${MODPATH:-$SCRIPT_DIR}
PKG="app.d2lock"
ACTIVITY="$PKG/.MainActivity"
LOCK_ACTIVITY="$PKG/.lockscreen.LockScreenActivity"
SERVICE="$PKG/.lockscreen.LockScreenService"
LOG="$MODDIR/logs/module.log"

resolve_apk() {
  for candidate in \
    "$MODDIR/payload/SamsungLockD2.apk" \
    "$SCRIPT_DIR/payload/SamsungLockD2.apk" \
    "/data/adb/modules/samsunglockd2/payload/SamsungLockD2.apk" \
    "/data/adb/modules_update/samsunglockd2/payload/SamsungLockD2.apk"
  do
    if [ -s "$candidate" ]; then
      APK="$candidate"
      return 0
    fi
  done

  # Module IDs can change between preview packages; locate the bundled APK
  # without depending on a hard-coded module directory name.
  for root in /data/adb/modules /data/adb/modules_update; do
    [ -d "$root" ] || continue
    candidate="$(find "$root" -maxdepth 3 -type f -path '*/payload/SamsungLockD2.apk' 2>/dev/null | head -n 1)"
    if [ -n "$candidate" ] && [ -s "$candidate" ]; then
      APK="$candidate"
      return 0
    fi
  done
  APK=""
  return 1
}

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

install_payload() {
  resolve_apk || {
    log_line "bundled APK not found; MODDIR=$MODDIR SCRIPT_DIR=$SCRIPT_DIR"
    return 2
  }
  # Recovery safety: never allow the boot module to downgrade a newer D2 APK.
  # pm install -d previously allowed an older bundled payload to replace the
  # app after reboot, which could make a verified build appear to roll back.
  log_line "installing bundled APK from $APK (downgrades blocked)"
  /system/bin/pm install -r "$APK" >> "$LOG" 2>&1
  rc=$?
  [ "$rc" -eq 0 ] && package_installed && return 0
  log_line "pm install returned $rc"
  return "$rc"
}

start_companion() {
  /system/bin/am start-foreground-service -n "$SERVICE" >> "$LOG" 2>&1
}
