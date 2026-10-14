# One D2 development baseline

Continue all D2 development on `fix/d2-widget-srp-diagnostics-20261014`, reviewed
through draft PR #71. The consolidation has both Build 1095 and Build 1106 in its
ancestry; neither feature set is a separate replacement build. Do not merge main
or publish a release without the owner's approval.

## Combined contents

- OneUIX port and compile stubs, categorized settings, LSPosed configuration
  Apply/read-back, package scope and app/framework hook routing.
- Framework, calls, camera, Settings, Notes, messaging, Health Monitor, SystemUI,
  launcher, gallery, network, SPen, browser, calendar/weather, watch and other
  imported customization implementations present in Build 1095. Availability
  remains feature/firmware dependent; bundled code is not proof of execution.
- Accessibility fallback, Root → Shizuku → accessibility → Device Admin sleep
  routes, bounded watchdog repair and call-policy fixes from Build 1095.
- D2 Hooks card, boot/wake registration and callback evidence, current-boot
  snapshots, actual first draw, first visible confirmation, SRP naming and
  last successful verification tracking from Build 1106.
- Compact/expanded SRP widget plus optional periodic scheduler observation count
  and elapsed event age. Credential observations remain separate from SRP.
- Optional KernelSU bridge, original APK signing workflow, tests and lint.
- OneUIX/DexKit notices, license texts, pinned DexKit source and complete
  corresponding D2 source archive restored with the imported components.

## Authentication policy retained

The imported legacy `disablePinVerifyPer72h` setting/method is retained for
configuration compatibility but now observes completed scheduler calls. Every
original framework invocation proceeds; no scheduler call is suppressed. The UI
calls this **Observe periodic Samsung PIN / pattern scheduling (72h diagnostics)**.
`observedSchedules` is a callback count, `suppressedSchedules` remains zero, and
the widget timer is event age, not a 72-hour deadline. Required Samsung primary
credentials, biometric prerequisites and strong-auth scheduling remain intact.
The existing guarded D2 power menu remains authoritative.

## Feature-preservation checks

`D2_FEATURE_BASELINE.json` records both source baselines, required module/source
files, critical wiring, APK feature classes and all four DexKit native ABIs.
CI runs `scripts/verify_feature_baseline.py` before compilation and checks the
built APK against the same baseline in `scripts/verify_deliverables.py`.
This makes another accidentally omitted OneUI module or Guardian diagnostic
surface a build failure instead of a deceptively smaller successful deliverable.
Intentional future feature changes must update this reviewed baseline explicitly.

## Validation

Run the standard Build APK and KernelSU module workflow on this branch. Check
unit tests, Android emulator tests, lint, original signer, module ZIP, APK feature
inventory and source archives. Physical S26 Ultra testing remains necessary for
Samsung private APIs, LSPosed scopes, saved customization settings, calls,
Direct Boot, wake/sleep and stability. Keep the optional bridge state and test
one imported customization group at a time after installing the combined APK.

## Verified combined build — 1107

- Code commit: `232336abe8c264b6f17f35371a856e05ffd86fe6` (parents include both
  previous development and Build 1095).
- [Successful workflow](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38631433723)
- [Download the combined deliverables](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38631433723/artifacts/11926312104)
- 40 unit tests and 27 emulator tests passed; zero failures/skips. Compilation,
  original signature verification, source packaging and feature inventory pass.
  Lint completes with 720 warnings, zero errors; this is not warning-free.
- Outer ZIP: 11,803,135 bytes; APK: 22,502,503 bytes; 19 DEX files and all four
  DexKit ABIs. Downloaded artifact checksums and every nested ZIP/APK verified.
- DEX inspection retains all named classes from both 1095 and 1106. Both tracked
  source inventories are retained; required source files are in the archive.
- Optional KernelSU scripts are byte-identical to 1095. The module remains
  optional and does not embed or mount the APK.
- Negative preservation test: the new APK guard rejects the smaller 1106
  artifact because its OneUI feature class is missing. The combined APK passes.
- Repository `AGENTS.md` names this branch as the canonical baseline for future
  work. The following documentation commit does not change the tested APK.

Install this combined APK over the current app, keep the tested KernelSU/module
configuration, and reboot normally for LSPosed hooks. Check Tools → Experimental:
OneUI feature port and D2 Hooks. Verify Apply read-back before testing one
customization group at a time. Test required Samsung credentials, fingerprint,
repeated wake/sleep, screen-off incoming calls and watchdog return. Firmware
compatibility and the perceived startup delay still require S26 Ultra results.
