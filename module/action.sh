#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

if package_enabled; then
  /system/bin/am start -n "$ACTIVITY" --activity-clear-top >/dev/null 2>&1
  log_line "module action opened D2 settings"
else
  log_line "module action failed: normal D2 installation missing or disabled"
  echo "Kiosk D2 Guardian is not installed/enabled."
  echo "Install the APK normally. KernelSU Root Mode is configured inside D2."
fi
