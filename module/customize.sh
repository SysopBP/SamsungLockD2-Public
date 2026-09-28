#!/system/bin/sh

ui_print "*******************************"
ui_print " Kiosk D2 Guardian - KSU"
ui_print "*******************************"

[ "$KSU" = "true" ] || abort "KernelSU / KernelSU Next is required."
[ "$API" -ge 31 ] || abort "Android 12 (API 31) or newer is required."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755

if [ -s "$MODPATH/system/priv-app/KioskD2Guardian/KioskD2Guardian.apk" ]; then
  ui_print "Priv-app overlay APK found."
  ui_print "Android will discover D2 during the system package scan."
else
  abort "Missing system/priv-app/KioskD2Guardian/KioskD2Guardian.apk"
fi

ui_print "No late pm install fallback is used."
ui_print "Reboot after module installation."
