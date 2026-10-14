# D2 accessibility screen-off experiment

Baseline: successful Build 1070, commit 81c707a91eb00bc9eb64f278834b2775e62ad750.
Preserved as baseline/build-1070. Main remains unchanged. All experiment changes
are on experiment/accessibility-screen-off.

The optional service exposes only Android's GLOBAL_ACTION_LOCK_SCREEN action.
Its XML requests no screen-content access, no gesture capability and no
accessibility event types. It does not authenticate or dismiss Samsung keyguard,
launch D2 in response to other apps, or act as a kiosk enforcement service.

The Android service must be enabled manually. The accessibility fallback is
separately disabled by default. The route order is Root (when Root Mode is on),
then authorized Shizuku, then optional accessibility, then device admin. Shizuku
can continue running as shell UID 2000; accessibility does not change its UID or
permissions. An unavailable or failed Shizuku request reaches accessibility; an
unavailable or rejected accessibility action reaches device admin.

## Test

1. Install the experimental APK as an update with the same signing key. Keep the
   Build 1070 APK available to reinstall; this is the same package, not a second app.
2. Open Readiness & guided tests → Accessibility lab → Open Android Accessibility
   settings. Enable D2 screen-off experiment and return to D2.
3. Confirm Connected, choose Test screen off now, then wake and authenticate.
   Check D2 fingerprint/PIN, Samsung face/PIN/pattern when requested, and relock.
4. Record observed result. An accepted global action is not proof of screen-off
   or successful biometrics. Samsung may require credentials; record that outcome.
5. Turn ON accessibility fallback. With Root Mode off and Shizuku authorized,
   double-tap an empty D2 area and verify the Shizuku route in logs. Stop Shizuku
   temporarily and repeat to exercise accessibility fallback; restore Shizuku
   afterward. Use the manual test to isolate accessibility without changing Root
   or Shizuku. Route logs distinguish each accepted request.
6. Disable the accessibility service and confirm the existing route still works.
7. Test a reboot/reconnect and repeat the observations. Android/firmware may
   restrict enabling a sideloaded accessibility service; do not assume it is bound
   merely because its switch appears enabled.

Disable D2 accessibility service from the lab or Android Accessibility settings
to end the test. The lab does not request unrestricted screen access or attempt
to enable itself automatically.

Build verification runs the existing unit, lint, APK and emulator workflow.
Physical Samsung accessibility/biometric behavior requires device testing.

## Integration and safety controls

System Integrations now has an **Accessibility screen-off fallback** switch tied
to the working service preference. Enable the Android service from **Accessibility
setup & tests** first. A disconnected, already-enabled fallback can still be
switched off. The old unrelated framework-recovery placeholder remains planned;
it is not the accessibility screen-off implementation.

System Integration Center, Safety Center, System Health and copied diagnostics
show accessibility connection/fallback state. Shizuku status reports the server
UID when its API provides one, including UID 2000 Shell. Root and Shizuku retain
their existing priority. Galaxy Island remains optional.

Accepted screen-off requests now record the selected route and timestamp for
Root, Shizuku, Accessibility and Device admin. This is request telemetry, separate
from the user's observed wake, authentication and relock results. Earlier tests
are not retroactively marked as verified.
