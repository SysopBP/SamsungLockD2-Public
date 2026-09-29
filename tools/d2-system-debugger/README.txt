D2 System Server Debugger 1.1.0 — KernelSU module

Install over version 1.0.0 in KernelSU Manager > Modules, then reboot.
After unlocking, allow 3–5 minutes for the boot archive in Downloads.
Tap Action after reproducing a problem to collect and export fresh evidence.
Termux alternative:
su -c 'sh /data/adb/modules/d2_system_debug/action.sh'

NEW IN 1.1.0
- Continuous separate Android crash buffer across ALL apps (not just D2).
- Separate crash/ANR/process-death event stream and D2 marker stream.
- Readers restart after disconnects; a soft reboot can share the same history.
- Read-only forensic snapshots at startup, approximately every five minutes,
  and in completed manual/boot captures: DropBox, process exit info, last ANR,
  readable text tombstones, ANR traces, kernel dmesg, pstore and last_kmsg.
- Separate low-volume early-D2 boot recording, protected from raw-log rotation.
- Summary also searches LSPosed logs so working hooks are not missed.
- Export copies live data to a staging snapshot before making the archive.

COVERAGE AND LIMITS
Captures retained Java crashes, native crash evidence, ANRs and watchdog
reports wherever Android exposes them. It cannot guarantee every crash:
pre-service failures, power loss, inaccessible sources and Android retention
can leave gaps. No hooks, services, SELinux policy, lock settings or log buffer
sizes are changed; no log buffers or crash records are cleared.

Crash watchers continue after the three-minute boot capture ends. The boot
archive does not update automatically afterward: tap Action for later crashes.
Action also includes up to three retained kernel-boot histories for recovery.
Reports label dates/boot IDs; old retained crashes are not new-boot failures.
A framework/soft reboot does not necessarily change the kernel boot ID.
No first-frame claim should be inferred from a boot-ready or focus event.

FILES
crash-history/watch_*/crashes.txt*: all-app Android crash buffer (24 MiB).
crash-events.txt*: crash/ANR/process-death events (4 MiB).
d2-events.txt*: selected D2, AndroidRuntime and Watchdog tags (8 MiB).
evidence/: latest bounded forensic snapshot plus source coverage/index files.
crash-evidence/: forensic snapshot taken by the manual/boot collector.
early-d2.txt*: separate selected-tag boot logs (8 MiB).
logcat.txt*: three-minute boot raw logs (12 MiB) or retained manual dump.
lsposed-*.txt: bounded tails from known LSPosed log directories.
export-status.txt: live-copy caveat and copy errors, if any.

Each forensic command is capped at 4 MiB / 20 seconds. Each of tombstones,
ANRs and pstore retains up to six newest readable non-protobuf files, capped
at 512 KiB each. Files may be truncated; indexes record source filenames.
The latest forensic snapshot replaces the previous snapshot; independent
crash/event streams continue rotating. This is bounded diagnostic retention,
not an unlimited crash archive. Unsupported commands keep their error output.

STORAGE AND RECOVERY
Internal: /data/adb/d2-system-debug/session_* and watch_*
Three recent sessions plus three kernel-boot histories are retained (active
captures are protected). Budget roughly 400 MiB internally, plus temporary
export space; Downloads archives remain until you delete them.
If Downloads is unavailable, unlock and tap Action. After a failed boot,
recover bootability and tap Action before further reboots rotate old evidence.
Outputs: /storage/emulated/0/Download/D2_Debug_session_*.tar.gz
Fallback: /sdcard/Download. No /data/media copy command is required.

Disabling/removing the module stops the crash watcher after its current bounded
snapshot/check. Reboot also stops the current boot collector. Reports remain.
Only after disabling/removing and rebooting, to delete retained diagnostics:
su -c 'rm -rf /data/adb/d2-system-debug'

Logs and crash reports can contain personal app details; review before sharing.
Host validation does not replace testing on your Android 17 / KernelSU device.
