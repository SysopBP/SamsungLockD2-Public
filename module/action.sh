#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

if ! package_installed; then
  install_payload
fi

if package_installed; then
  /system/bin/am start -n "$ACTIVITY" --activity-clear-top >/dev/null 2>&1
  log_line "module action opened settings"
else
  log_line "module action failed: companion APK missing"
  echo "Samsung Lock D2 APK is not installed or bundled."
  echo "See README.md inside the module."
fi
