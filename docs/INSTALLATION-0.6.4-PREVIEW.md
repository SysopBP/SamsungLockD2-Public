# D2 Guardian 0.6.4 Preview — Installation and fingerprint setup

This guide covers the Samsung Recovery Protection (D2 SRP) and fingerprint-login preview.

**Enroll first:** configure a Samsung screen-lock PIN and enroll a fingerprint in Samsung biometrics before disabling Samsung's **Fingerprint unlock** switch. Keep both the Samsung PIN and fingerprint enrollment.

**Known limitation:** D2 fingerprint authenticates D2, but Samsung PIN is still required afterward in current device tests. The first device unlock after reboot also requires Samsung PIN.

## Requirements

- Compatible Samsung firmware; current device findings are from SM-S948U1, Android 17 / One UI 9.
- Root for D2's root-assisted Guardian/kiosk functions and an installed, working LSPosed environment for SRP and power-menu hooks.
- D2 enabled in **System Framework** and **SystemUI** LSPosed scopes.
- The APK supplied with the selected prerelease. Match its published version and checksum when available.

Shizuku is an optional privilege path for supported D2 features. It does not replace the Xposed System Server hook used for SRP. The optional KernelSU bridge is separate from LSPosed; installing it alone does not activate these hooks.

## 1. Install or update D2

1. If updating, leave kiosk using your existing D2 credential.
2. Download and install the D2 APK from the selected prerelease. Allow installation from that source when Android requests it.
3. Open D2 and configure or confirm your D2 recovery PIN/pattern. Keep this credential available.
4. Grant root through your root manager when D2 requests it for the functions you enable.
5. If using the optional KernelSU bridge, install the matching ZIP through your module manager. Install the APK separately too: the bridge preserves an already-installed app.
6. Enable D2 in LSPosed and select **System Framework** and **SystemUI**. Reboot to load the hooks; an APK update alone does not prove they loaded.

If Android reports a signing conflict, use the matching signed update. Do not uninstall merely to bypass the message: uninstalling/clearing data loses D2 settings and its configured credential. Older package IDs may install separately; disable the older Guardian before enabling the new one.

## 2. Enroll Samsung PIN and fingerprint FIRST

1. In Samsung Settings, configure a **screen-lock PIN** if one is not already set.
2. Search Settings for **Fingerprints** and open Samsung's biometric settings.
3. Authenticate with your Samsung PIN and add at least one fingerprint.
4. After enrollment, turn **Fingerprint unlock OFF**.
5. Keep the Samsung screen-lock PIN and enrolled fingerprint. **Do not delete fingerprints or remove the Samsung PIN as part of this setup.**

The OFF switch disables Samsung lock-screen fingerprint unlock while retaining enrollment for D2's biometric prompt. It does not disable all biometric use or remove Samsung's device-credential requirement.

## 3. Select fingerprint login in D2

1. Open D2 and authorize access with your D2 recovery credential.
2. Open **Security Center → Unlock method**.
3. Choose **Login with fingerprint**.
4. Test the actual D2 login, then test its PIN/pattern fallback.

**Test Fingerprint** in Fingerprint Center is a sensor diagnostic. Passing that test does not unlock D2 or authorize protected settings.

## 4. Verify SRP after reboot

1. Complete the Samsung PIN unlock when requested.
2. Open D2's **View feature status**, or **D2 SRP status** from the integrated power menu.
3. Confirm a fresh System Server result and successful vault read-back.

| SRP target | Expected result |
| --- | --- |
| Screen-lock vault flag | Disabled |
| Maintenance Mode vault flag | Disabled |
| Download Mode AT-command flag | Enabled |
| Derived Download Mode eligibility | Allowed |

These vault flags are separate from Samsung/Android credentials. “Screen-lock flag disabled” does not mean your Samsung PIN has been removed. The AT-command flag result is not a test of an AT-command response. An unknown or failed result must not be treated as verified.

Completed PIN/password/pattern changes and biometric enrollment changes schedule SRP refreshes after two and twelve seconds; firmware-specific detection still needs validation. Check a fresh status after changing security setup.

## 5. Check the normal unlock flow

With Guardian/kiosk enabled, lock and wake the phone, authenticate using D2 fingerprint, and follow the Samsung PIN handoff when it appears. Test cancellation and D2 PIN/pattern fallback. Keep both Samsung and D2 credentials available.

D2 may remain above Samsung keyguard during presentation, but that does not authenticate Samsung keyguard. Repeated handoff screens and Samsung PIN after D2 fingerprint are known preview limitations.

## Power actions and protection limits

Supported hooked power-off/restart paths and orderly soft restart perform fresh SRP checks. Failed/unknown verification or a five-second timeout cancels a covered request; retry explicitly after resolving the status. Opening SRP status alone does not restart the phone.

Missing or unsupported shutdown hooks may leave stock Samsung power actions unguarded. D2's own soft restart is refused when its guard is unavailable. Direct root zygote/process restarts, init/property restarts, hardware forced resets and power loss bypass these protections. Emergency thermal/battery shutdown remains immediate. A verified read does not guarantee firmware persistence or prevent later Samsung writes.

## Troubleshooting

- **Fingerprint unavailable:** confirm Samsung PIN and fingerprint enrollment exist, confirm D2 fingerprint login is selected, and use D2 PIN/pattern fallback.
- **Samsung PIN appears after fingerprint:** expected limitation of this preview; enter the Samsung PIN.
- **SRP unknown or power-menu additions missing:** check LSPosed scopes and reboot. Firmware APIs/hooks may be unsupported; check diagnostics rather than assuming protection.
- **Disabled framework/Galaxy Island controls:** these are marked in development and are intentionally unavailable.
- **Forgotten D2 credential:** there is no master PIN. Clearing app data resets D2 and its settings; do not do this expecting to retain configuration.

For diagnostics, use /storage/emulated/0/Download by default. Check hook installation, verification and cancellation results in the Guardian logs. 

## Credits

**@diablOw** originally discovered the Samsung recovery behavior behind D2 SRP. **SoClear / OneUIX** inspired the independent Samsung power-menu action integration. See [release notes and acknowledgments](RELEASE-0.6.4-PREVIEW.md), [third-party notices](../THIRD_PARTY_NOTICES.txt), and [license](../LICENSE).
