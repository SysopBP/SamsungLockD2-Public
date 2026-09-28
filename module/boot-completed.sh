#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

# KernelSU may invoke this hook near the framework boot-complete transition.
# Keep it intentionally passive so it cannot race the verified LSPosed
# ActivityManagerService.systemReady path. service.sh performs the one optional
# post-boot start after sys.boot_completed=1.
if package_enabled; then
  log_line "boot-completed observed; D2 installed; no duplicate start requested"
else
  log_line "boot-completed observed; D2 missing or disabled; bridge idle"
fi
