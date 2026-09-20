#!/system/bin/sh

ui_print "*******************************"
ui_print " Samsung Lock D2 - KSU Next"
ui_print "*******************************"

[ "$KSU" = "true" ] || abort "KernelSU / KernelSU Next is required."
[ "$API" -ge 31 ] || abort "Android 12 (API 31) or newer is required."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755

if [ -s "$MODPATH/payload/SamsungLockD2.apk" ]; then
  ui_print "Companion APK found; it will install after boot."
else
  ui_print "Companion APK is not bundled in this developer package."
  ui_print "Build the included Android project, then copy app-release.apk to:"
  ui_print "payload/SamsungLockD2.apk and re-zip the module."
fi

ui_print "No /system files, lock credentials, Knox data, or SELinux policy are modified."
