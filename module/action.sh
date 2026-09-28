#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

if package_installed; then
  /system/bin/am start -n "$ACTIVITY" --activity-clear-top >/dev/null 2>&1
  log_line "module action opened settings"
else
  log_line "module action failed: priv-app overlay package missing"
  echo "Kiosk D2 Guardian is not visible to PackageManager."
  echo "Reboot after installing/enabling the KernelSU module."
fi
