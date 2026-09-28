#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

if package_installed; then
  start_companion
  log_line "boot-completed start requested"
else
  log_line "boot-completed: priv-app overlay package not detected"
fi
