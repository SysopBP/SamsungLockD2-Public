#!/system/bin/sh

ui_print "*******************************"
ui_print " Kiosk D2 Guardian - KSU Bridge"
ui_print "*******************************"

[ "$KSU" = "true" ] || abort "KernelSU / KernelSU Next is required."
[ "$API" -ge 31 ] || abort "Android 12 (API 31) or newer is required."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755

ui_print "D2 is NOT installed as a system/priv-app package."
ui_print "UID 1000 is NOT requested by this module."
ui_print "Install the D2 APK normally, then reboot."
