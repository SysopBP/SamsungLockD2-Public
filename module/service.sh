#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

log_line "late-start service entered"
wait_for_android || {
  log_line "boot completion timeout"
  exit 0
}

if package_installed; then
  log_line "normal user-installed D2 package detected"
  start_companion
  log_line "foreground companion start requested"
else
  log_line "D2 is not installed; bridge remains idle"
fi
