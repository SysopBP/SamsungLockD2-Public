#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

log_line "late-start service entered"
wait_for_android || {
  log_line "boot completion timeout"
  exit 0
}

install_payload
result=$?
case "$result" in
  0) log_line "companion package ready" ;;
  2) log_line "no bundled APK; waiting for manual companion installation" ;;
  *) log_line "APK installation failed with code $result" ;;
esac

if package_installed; then
  start_companion
  log_line "foreground companion start requested"
fi
