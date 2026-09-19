# Samsung Lock D2 — KernelSU Next Bridge

This flashable module complements the Samsung Lock D2 Android app. It does not replace the secure Samsung lock screen.

## What the module does

- Verifies installation through KernelSU/KernelSU Next.
- Optionally installs a bundled companion APK after Android boots.
- Starts the app's foreground lock-screen service during late start and boot completion.
- Opens the app settings through the KernelSU module Action button.
- Keeps a small diagnostic log at `logs/module.log`.
- Preserves the companion app and its data when the module is removed.

## Developer package: add the APK

GitHub release ZIPs already contain the tested debug APK. To package your own build:

1. Open the separate `SamsungLockD2` Android Studio source project.
2. Install Android SDK Platform 37 and build a signed release APK.
3. Rename the output to `SamsungLockD2.apk`.
4. Put it in `payload/SamsungLockD2.apk` inside this module folder.
5. Zip the *contents* of this folder so `module.prop` is at the ZIP root.
6. Install the ZIP from KernelSU Next Manager and reboot.

Without the bundled APK, the module still works as a bridge after the companion app is installed normally.

## First use

1. Open KernelSU Next and grant root to Samsung Lock D2 when requested.
2. Tap the module's Action button.
3. Create your independent six-digit D2 PIN. Feature permissions and notification-listener access are optional.
4. Choose a wallpaper. Double-tap the D2 button or add its home-screen widget.
5. Optionally enable Show D2 when the screen wakes and KernelSU root mode in the app.
6. For PIN-only normal exit, enable Require PIN to leave D2 (root kiosk). Confirm Kiosk active on the lock screen before relying on Home/Recents blocking.

Install the APK explicitly to update an existing app; the module only installs it when absent. The bridge ID is retained for upgrade compatibility. D2 is an app privacy screen. Without active kiosk, system navigation bypasses it. With kiosk, the D2 PIN is needed for normal exit, while root, recovery, and reboot remain bypasses. It cannot repair firmware or guarantee prevention of Samsung D2 errors.

## Safety

The module uses no `/system` overlay, so `skip_mount` is present and no metamodule is required. It does not alter Gatekeeper, Weaver, locksettings, PINs, passwords, biometrics, Knox, SELinux rules, or system properties. The optional app kiosk feature also uses a temporary Android task allowlist and task-feature flags. Android kiosk mode interacts with keyguard internally; no Samsung PIN is created. The helper releases restrictions when D2 disconnects or UI heartbeats stop for about 20 seconds. Reboot is the fallback recovery. See the app README for limitations; no device-owner provisioning or persistent device-policy writes are performed.
