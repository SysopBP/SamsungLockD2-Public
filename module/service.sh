#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

log_line "optional bridge late-start entered; waiting for Android boot completion"

wait_for_android || {
  log_line "boot completion timeout; fail-open with no D2 action"
  exit 0
}

# Deliberately add a small post-boot settle period. The verified release path
# gets its early BOOT_READY signal from LSPosed ActivityManagerService.systemReady;
# this optional KSU module must not race or duplicate that early handoff.
sleep 5

if package_enabled; then
  log_line "normal user-installed D2 detected after boot"
  if start_companion; then
    log_line "post-boot companion start requested"
  else
    log_line "post-boot companion start request failed; fail-open"
  fi
else
  log_line "D2 missing or disabled; optional bridge remains idle"
fi
