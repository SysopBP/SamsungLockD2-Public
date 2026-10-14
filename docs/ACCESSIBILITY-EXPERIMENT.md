# D2 accessibility screen-off experiment

Baseline: successful Build 1070, commit 81c707a91eb00bc9eb64f278834b2775e62ad750.
Preserved as baseline/build-1070. Main remains unchanged. All experiment changes
are on experiment/accessibility-screen-off.

The optional service exposes only Android's GLOBAL_ACTION_LOCK_SCREEN action.
Its XML requests no screen-content access, no gesture capability and no
accessibility event types. It does not authenticate or dismiss Samsung keyguard,
launch D2 in response to other apps, or act as a kiosk enforcement service.

The Android service must be enabled manually. The double-tap test option is
separately disabled by default. When selected, D2 tries accessibility before its
usual Root/Shizuku/device-admin routes for its existing double-tap gesture.
Unavailable or rejected accessibility actions fall through to the existing paths.

## Test

1. Install the experimental APK as an update with the same signing key. Keep the
   Build 1070 APK available to reinstall; this is the same package, not a second app.
2. Open Readiness & guided tests → Accessibility lab → Open Android Accessibility
   settings. Enable D2 screen-off experiment and return to D2.
3. Confirm Connected, choose Test screen off now, then wake and authenticate.
   Check D2 fingerprint/PIN, Samsung face/PIN/pattern when requested, and relock.
4. Record observed result. An accepted global action is not proof of screen-off
   or successful biometrics. Samsung may require credentials; record that outcome.
5. Turn ON accessibility double-tap test and repeat using D2's existing empty-space
   double tap. Root may stay enabled because accessibility is tried first here.
6. Disable the accessibility service and confirm the existing route still works.
7. Test a reboot/reconnect and repeat the observations. Android/firmware may
   restrict enabling a sideloaded accessibility service; do not assume it is bound
   merely because its switch appears enabled.

Disable D2 accessibility service from the lab or Android Accessibility settings
to end the test. The lab does not request unrestricted screen access or attempt
to enable itself automatically.

Build verification runs the existing unit, lint, APK and emulator workflow.
Physical Samsung accessibility/biometric behavior requires device testing.
