# D2 Guardian Build 1104 continuation

## Exact artifact comparison

Downloaded both GitHub Actions deliverables and tested outer/inner ZIP integrity.
1095: run 38620160083, commit fa86939e901f29ec918df1a5d566e1d1aec98c6d,
branch `fix/periodic-credential-72h`.
1104: run 38626573243, commit 373621a1bd87c15b7d55d61d311c1e41dc9a2077,
branch `fix/d2-widget-srp-diagnostics-20261014`.
Their common ancestor is 81c707a91eb00bc9eb64f278834b2775e62ad750.
1104 is not a descendant of 1095. This is a divergent feature baseline, not evidence
that the six widget commits deleted the experimental features.

| Component | 1095 | 1104 |
| --- | ---: | ---: |
| Outer ZIP bytes | 11,763,596 | 9,251,198 |
| APK bytes | 22,486,047 | 18,877,958 |
| APK compressed in deliverables | 8,322,820 | 6,857,676 |
| DEX count / total bytes | 19 / 17,795,680 | 15 / 15,725,564 |
| Native libraries count / bytes | 4 / 1,451,292 | 0 / 0 |
| Asset count / bytes | 7 / 148,058 | 3 / 55,425 |
| Resource entries / bytes | 1,481 / 1,283,782 | 1,480 / 1,282,666 |
| resources.arsc bytes | 2,262,872 | 2,261,180 |
| Source ZIP bytes | 2,591,112 | 2,428,911 |
| KernelSU ZIP bytes | 26,801 | 13,734 |

Outer ZIP reduction: **2,512,398 bytes**. Contributions to compressed payload reduction:
APK 1,465,144; source ZIP 150,411; KernelSU ZIP 13,109;
omitted DexKit source ZIP 882,454; omitted source metadata 482;
checksums 62; dependency report 162. Remaining 574 bytes are ZIP directory/header overhead.
The four native libraries in 1095 are `libdexkit.so` for arm64-v8a,
armeabi-v7a, x86 and x86_64; 1104 has no native dependency. Parsed DEX class
definitions: 10,419 classes in 1095 versus 9,190 in 1104; 1,243 classes appear
only in 1095 and 14 only in 1104. Missing experiment classes include OneUiPortSettings,
PeriodicCredentialHook/Settings, D2AccessibilityService, WatchdogRecoveryBudget,
CallStatePolicy, FiveGTileService/Worker and FrameworkControlHooks. Their source
history confirms they belong to the separate experiment branch.
The experiment branch also contains OneUI-port, serialization JSON and libxposed
service dependencies, wider Xposed scope, accessibility, periodic credential,
call-policy and watchdog enhancements that never landed on the selected branch.
The source workflow changed from corresponding-source/dependency-source scripts
to a simple source ZIP. No binary corruption or missing KernelSU runtime script
was found. These are real content differences, not merely better compression.

KernelSU scripts, module.prop, paired setup, README, skip_mount and placeholder
have identical hashes between builds. LICENSE and THIRD_PARTY_NOTICES differ.
The bridge intentionally has no embedded APK or system mount: install D2 normally.
Do not enable the optional bridge merely to compensate for the smaller ZIP.

## Incremental implementation

Keep all 1104 features and branch history. Preserve Samsung vault name `DMC`,
legacy preference keys, class interfaces and DMC broadcast action strings.
User-visible diagnostics and log messages use D2 SRP — Samsung Recovery Protection.

D2 Hooks near the top of the main screen presents current-boot registration
counts, failures, unavailable targets, executed callbacks, worker snapshot time,
last delivered successful boot/wake event, wake deduplication and cumulative
Guardian suppression. Zero callbacks never means successful execution.
System Server status is evidence observed during this boot, not a live heartbeat.

BOOT_READY is queued after the original systemReady returns. Broadcast and
recovery work no longer run on the systemReady callback stack. Wake remains
post-call and queued off the framework wake lock. No credential hook changes
arguments, scheduling, results, or authentication flags.

Read-only strong-auth observers discover scheduler and request methods by name
and void instance return type, count successful calls across all users and
publish coalesced snapshots from a dedicated worker. They intentionally do not
interpret OEM argument positions or claim a 72-hour deadline. Installation,
partial failure, missing target and zero/observed calls remain distinct.
PIN and pattern credentials remain Samsung-controlled; fingerprint still requires
Samsung's separate strong-auth prerequisites. An observed timeout scheduler is
not proof that biometric authentication succeeded or that timeout enforcement
was disabled. Legacy bypass settings are neither read nor applied.

Widgets retain compact/expanded rows, add current-boot hook/credential evidence,
and record last successful SRP verification separately from last attempted check.
AT status is the verified vault flag, not an AT command response; Download Mode
status is derived eligibility, not a test of entering Download Mode.

## Device evidence and boot delay

Saved October 14 diagnostics report strongAuthRequired=0x0, but no matching
D2 periodic hook registration/callback evidence. A persisted switch and an SRP
read-back cannot prove that a periodic hook installed or ran. These logs do not
establish the precise firmware scheduler signature or a complete 72-hour test.

D2_Launch_Timeline records BOOT_READY 1792004088157, root request 1792004088180,
activity created 1792004088304, first recorded decor callback 1792004088364:
147 ms to activity creation and 207 ms to the callback. A later onResume at
1792004091835 is 3,678 ms after BOOT_READY. Similar earlier cycles show first
presentation around 200 ms followed by another resume several seconds later.
Therefore the trace does not support attributing the full perceived 3.5 seconds
to slow root launch. Window/keyguard transitions and surface reassertion need a
new device trace. The old FIRST_DRAW marker was a posted runnable, not proof of
actual draw; it now observes OnDraw and records its monotonic time. First boot
visible confirmation is no longer overwritten by every later resume.

No risky watchdog, incoming-call, authentication or relock policy rewrite is
included based solely on the timing symptoms. Full OneUI experiment porting and
accessibility/watchdog/call enhancements remain on the experiment branch and
need separate integration and device validation, including license preservation.

## S26 Ultra validation

1. Install the new APK over the current app. Keep the tested KernelSU root grant,
   LSPosed System Framework and SystemUI scopes, and optional bridge state.
2. Reboot normally. Record Samsung screen → first D2 → usable fingerprint timing.
   Open D2 Hooks: installation counts and callback counts must be separate.
3. Wake/sleep five times. Wake counters should grow; duplicate callbacks may grow
   while delivery is coalesced. An unavailable target must remain unavailable.
4. Confirm D2 fingerprint, D2 PIN/pattern fallback and Samsung required PIN/pattern.
   After reboot or required strong auth, Samsung credentials must still be required.
   Do not force timeout flags or simulate unlocks. Observe natural periodic events.
5. Test incoming call with screen off, caller display, answering, ending, voicemail,
   return to lock surface, and watchdog recovery. Check for black screens/reboots.
6. Resize widget small/medium/large and check large font settings. Compact views
   necessarily omit detail; expanded views show SRP flags, event and suppression
   counts, hook health, separate credential evidence and last SRP success time.
7. Export diagnostics from Termux to Download using the command below. Include
   the D2 Hooks screenshot and physical timing with results.

```sh
su -c '
{
date
dumpsys package app.d2lock | grep -E "versionCode=|versionName="
cat /data/user_de/0/app.d2lock/shared_prefs/d2_hook_evidence.xml
cat /data/user_de/0/app.d2lock/shared_prefs/guardian_xposed_health.xml
logcat -b all -d -v threadtime -s D2XposedBridge:V KeyguardSignalReceiver:V SamsungLockD2:V D2SRP:V
} > /storage/emulated/0/Download/D2_1104_Continuation_Test.txt 2>&1
'
```

CI compiles, runs unit/instrumentation runtime checks and lint, verifies the
original signing certificate and inventories APK/module contents. Emulator CI
cannot validate Samsung private APIs, KernelSU, LSPosed callbacks, Direct Boot
on this firmware, real calls, 72-hour expiry or System Server stability under
physical-device use. No main merge or release publication is authorized.

## Completed validation — Build 1106

- Code commit: 2fd96a9384f4d0131fbc5f95c5c4fe49a87d6a97.
- Successful run: https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38629193811
- Deliverables: https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38629193811/artifacts/11925122984
- 27 unit tests and 25 Android 16 emulator instrumentation tests passed, zero failures.
- Lint completed with **655 warnings and no errors**. It is not a warning-free build.
  The new observer findings include reflective PrivateApi, singleton application
  Context/Handler lifetime and UseKtx warnings. Firmware reflection is guarded;
  workers retain process/application state, not Activity instances. These warnings
  are disclosed rather than hidden with new suppressions.
- Original signing certificate verified by CI; APK/module entry-point and inventory
  checks passed. Outer artifact is 9,290,741 bytes; APK is 18,896,670 bytes.
- Downloaded final artifact and verified every nested ZIP/APK and all SHA256SUMS.
  KernelSU scripts remain byte-identical to 1104; only the discovery-credit wording
  changed in its notices. No APK was added to the optional bridge.
- DEX comparison preserves every non-synthetic class in 1104. Three generated
  lambda names changed with compilation; replacement lambda classes are present.
  CredentialObserver, HookDiagnostics and actual draw observer are included.
- Physical S26 Ultra validation is still required. This build does not claim to
  fix the entire perceived startup delay or to have completed a 72-hour expiry test.

A subsequent documentation-only commit updates this audit and remaining prose
names; the successful APK is built from the code commit linked above.
