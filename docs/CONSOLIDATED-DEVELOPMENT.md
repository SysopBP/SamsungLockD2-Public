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
