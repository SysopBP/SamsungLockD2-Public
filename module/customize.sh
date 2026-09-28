#!/system/bin/sh

ui_print "***************************************"
ui_print " Kiosk D2 Guardian - OPTIONAL KSU Bridge"
ui_print "***************************************"

[ "$KSU" = "true" ] || abort "KernelSU / KernelSU Next is required."
[ "$API" -ge 31 ] || abort "Android 12 (API 31) or newer is required."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755

ui_print ""
ui_print "This module is NOT required for the verified stable path."
ui_print "D2 stays a normal user-installed APK."
ui_print "No /system/priv-app overlay. No UID 1000 request."
ui_print "No early-boot framework hooks are installed by this module."
ui_print ""
ui_print "Verified path:"
ui_print "  1. Install D2 APK normally"
ui_print "  2. Enable KernelSU Root Mode inside D2"
ui_print "  3. Enable D2 in LSPosed with the documented scopes"
ui_print ""
ui_print "This optional module only provides a conservative post-boot"
ui_print "startup/diagnostic bridge after sys.boot_completed=1."
