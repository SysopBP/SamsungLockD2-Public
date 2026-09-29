#!/system/bin/sh
# Bound binder calls so a stuck service cannot stall the collector forever.
run() {
  echo
  echo "===== $* ====="
  timeout 6 "$@" 2>&1
  code=$?
  [ "$code" = 0 ] || echo "Command status: $code (unsupported, denied or timed out)"
}
echo "D2 System Server Debugger snapshot: $(date)"
echo "Uptime: $(cat /proc/uptime)"
echo "Boot ID: $(cat /proc/sys/kernel/random/boot_id)"
run id
for key in ro.product.model ro.build.version.release ro.build.version.sdk ro.build.fingerprint sys.boot_completed dev.bootcomplete init.svc.bootanim sys.usb.state; do
  echo "$key=$(getprop "$key")"
done
run getenforce
run cmd user is-user-unlocked 0
run ps -A -o USER,PID,PPID,NAME
run dumpsys activity services app.d2lock
run dumpsys package app.d2lock
# Keep focus/window and process evidence, without notification contents or prefs.
run dumpsys window policy
run dumpsys activity top
run dumpsys power
run logcat -g
echo '===== MODULE METADATA ====='
for d in /data/adb/modules/*; do
  [ -f "$d/module.prop" ] || continue
  echo "$d"
  head -12 "$d/module.prop"
  [ -f "$d/disable" ] && echo DISABLED
done
echo '===== LSP LOG DIRECTORY METADATA ====='
for d in /data/adb/lspd/log /data/adb/lsposed/log; do
  [ -d "$d" ] && ls -lt "$d" | head -15
done
exit 0
