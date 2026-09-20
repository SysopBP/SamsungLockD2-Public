#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

package_installed || install_payload
if package_installed; then
  start_companion
  log_line "boot-completed start requested"
fi
