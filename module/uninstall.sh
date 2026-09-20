#!/system/bin/sh

MODDIR=${0%/*}
. "$MODDIR/common.sh"

/system/bin/am stopservice -n "$SERVICE" >/dev/null 2>&1
log_line "module removed; companion app and user data preserved"

# To explicitly remove the APK with the module, create an empty file named
# uninstall_app in the module directory before uninstalling.
if [ -f "$MODDIR/uninstall_app" ]; then
  /system/bin/pm uninstall "$PKG" >/dev/null 2>&1
fi
