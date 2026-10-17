# Hook diagnostics investigation — 2026-10-14

## Evidence and causes

Current canonical branch already contains unified Build 1107 (232336a), following
1104 and the OneUIX consolidation. Continue from head 121a735, not 1104.
Build 1104 artifact is 9,251,198 bytes. The consolidated 1107 artifact is
11,803,135 bytes and its preservation guard includes OneUIX modules/native ABIs.
The earlier packaging omission is documented in CONSOLIDATED-DEVELOPMENT.md.

D2_Hook_Diagnostics.txt shows runtime PID 14741: the OneUI observer installed
public final void rescheduleStrongAuthTimeoutAlarm(int,long) and observed 3
completed calls. PeriodicCredentialHook independently rejected that signature:
SchedulerSignature required long(s) followed by int. CredentialObserver uses
name/void/nonstatic discovery without argument interpretation, explaining its
independent installed status. These are different observers, not contradictory
results from a single registration. Fix explicitly accepts int,long, maps user
index to 0, retains known long,int and long,long,int layouts, and rejects unknown
or ambiguous layouts. Every hook still invokes chain.proceed exactly once.
The narrow observer counts successful user-0 calls; broad observers cover all
users. Counts never establish interception/suppression or credential validation.

HookDiagnostics previously read every numeric Intent extra using getLongExtra.
GuardianXposedBridge supplies AtomicInteger registration counters as Int, while
CredentialObserver supplies Long counters. Android does not coerce these Bundle
types: persisted registration values could default to -1. Normalize Number at
the receiving app worker; add mixed-type regression coverage. This is a confirmed
reporting defect, not evidence that installed wake interception failed.
The legacy wake_hook_status string is neither read nor written by the current
branch. Consult d2_hook_evidence current-boot counters; legacy preferences cannot
establish current availability. Device logs explicitly show wake installation,
GUARDIAN_WAKE_SENT and Guardian REASSERT plus verified D2 fingerprint events.

## Configuration and status meaning

OneUIX's installed external app settings do not configure D2. D2 embeds an
attributed OneUIX port with its own Apply/read-back and configuration namespace.
The legacy disablePinVerifyPer72h key now enables read-only observation in the
D2 port; original Samsung scheduler calls always proceed. A saved true value is
not proof of suppression. External OneUIX's own active module/configuration must
be checked independently. A narrow package-name search does not prove absence.

Current status separates registration (installed/unavailable/failed), completed
callback counts and current-boot freshness. There is no verified credential
suppression status. Snapshot version 3 explicitly sets featureVerified=false and
identifies callback scope. No authentication weakening is implemented.

## SRP, boot and other modules

DmcVaultPolicy.matchesTarget requires slot bytes (0,0,1). downloadAllowed derives
(data[0]==0 || data[1]==1 || data[2]==1), so true is expected for that recovery
policy. It refers to the interpreted vault download policy, not successful
entry into download mode, permission to unlock or credential authentication.
Do not change this existing recovery policy on the basis of this log alone.

CorePatch ScanPackageUtilsHook failures belong to org.lsposed.corepatch; D2 boot
and wake hooks install afterward in the same process. No supplied stack trace
links that failure to LockSettingsStrongAuth. The isolated jobscheduler death
has no matching crash trace/timestamp correlation establishing D2 causality.
Obtain crash/system logs around any repeat before attributing it.

No boot/wake launch logic, watchdog budget, fingerprint route, SRP enforcement,
KernelSU scripts, Samsung fallback, OneUIX settings or scopes are changed here.
Normalization runs in the existing receiver worker; signature matching occurs
only during registration. No new blocking callback operation or launch added.

## Installed APK identity

versionCode 30 and versionName 0.6.4-oneui-hooks-experiment are shared across
several revisions, including 1104 and 1107: they cannot identify build 1104.
No installed APK bytes or SHA-256 were supplied. Exact identity remains pending
read-only device hash comparison against CI SHA256SUMS.txt. New CI APKs contain
assets/D2-BUILD.txt with commit, run number and run ID to make future identification
unambiguous. A full reboot is needed to reload LSPosed code after APK updates;
a foreground manager version alone cannot identify the code inside system_server.

## Safe device verification

Run in Termux (reads only; creates one diagnostic text file in Download):

```sh
su -c '
date
getprop ro.product.model
getprop ro.build.version.sdk
pidof system_server
dumpsys package app.d2lock | grep -E "versionCode=|versionName="
pm path app.d2lock | while IFS= read -r entry; do
  sha256sum "${entry#package:}"
done
settings get global d2_periodic_credential_hook_status
cat /data/user_de/0/app.d2lock/shared_prefs/d2_hook_evidence.xml
logcat -b all -d -v threadtime -s D2PeriodicAuth:V D2XposedBridge:V AndroidRuntime:E
' > /storage/emulated/0/Download/D2_Hook_Verification.txt 2>&1
```

Compare base APK hash with the artifact SHA256SUMS.txt. Check snapshot boot/PID
against live process, scheduler signature int,long, installed then completed
callbacks after normal device use, and no unavailable signature error. Do not
force strong-auth timeouts, clear flags, change credentials or suppress alarms.
After installation and ordinary reboot, test Samsung-required PIN/pattern,
D2 fingerprint, wake/reassert, incoming calls with screen off, SRP widget,
OneUI Apply/read-back and settings retention. Firmware hooks and perceived boot
latency require the physical S26 Ultra; emulator/CI cannot verify these claims.

## Completed CI and artifact verification — Build 1108

Code commit: f8155f3166d63e0f5f9de49290308e7ef08ac89a.
Run: https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38634713363
Unified deliverables: https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38634713363/artifacts/11928016856

41 unit tests and 27 emulator tests passed with zero failures/errors/skips.
Lint: 720 warnings, zero errors. Original signing verification passed.
Downloaded artifact: 11,810,297-byte ZIP, 22,502,688-byte APK, 19 DEX;
all required classes and all four DexKit ABIs retained. New numeric normalization
class present; build-identity asset matches exact run and commit. All checksums
and nested archive integrity checks pass. KernelSU scripts are byte-identical
to the unified 1107 artifact.
APK SHA-256: 8b5f28779381c926e71f735a6feacc1735b60d1c3f9a8625c29901c8e01f1225.

Independently downloaded 1104 APK SHA-256:
c2ab5be956ae7e3a14abc74d3b981183812dce9f0069901720493f166a591be2.
It lacks PeriodicCredentialHook, CredentialObserver and OneUiPortSettings,
confirming that supplied periodic runtime logs include code absent from 1104.
This is not an exact identification of the installed APK; device hash comparison
remains required. Independently downloaded 1107 passes every unified class/ABI
baseline check. This documentation update does not trigger a competing build.

Physical S26 Ultra behavior remains unverified by CI. Use the read-only commands
above after installing and ordinarily rebooting; check that Samsung credential
requirements, D2 fingerprint, OneUI settings, calls and boot/wake behavior remain
correct. No credential-suppression feature is claimed.
