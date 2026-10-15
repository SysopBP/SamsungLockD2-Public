# Authenticated Samsung keyguard handoff

D2 PIN, pattern and app fingerprint success now share one completion path. D2 sends an identity-sharing, package-targeted broadcast with its current presentation session. The SystemUI hook checks the sender UID, exclusive package ownership of that UID, original APK signing certificate, live presentation lease, session, interactive screen, primary foreground user, unlocked credential storage and zero Samsung strong-auth flags before invoking the captured KeyguardViewMediator.keyguardDone(userId).

This intentionally makes verified D2 authentication authoritative for ordinary subsequent keyguard dismissal. It does not verify the Samsung PIN. Samsung remains responsible for the first credential unlock after boot, lockdown, lockout and other strong-auth requirements. Missing or changed hidden APIs fail closed for dismissal; the existing presentation hook remains available.

D2 polls both isKeyguardLocked and isDeviceLocked for up to five seconds before releasing its kiosk and closing. Timeout keeps D2 open and offers Samsung authentication for recovery. No framework credential, biometric result, PIN store or strong-auth state is modified.

## Device validation required

- Enable the D2 LSPosed scope for SystemUI and reboot to load the changed hook.
- Complete the first Samsung credential unlock after reboot.
- Lock and unlock with D2 PIN, pattern and fingerprint separately; verify direct return to the launcher without a Samsung PIN panel.
- Repeat screen-off/wake cycles and confirm kiosk/watchdog recovery still works.
- Test lockdown and first boot: Samsung credentials must remain required.
- Disable the SystemUI hook: D2 must stay visible on handoff timeout and offer recovery.
- Logs: D2_KEYGUARD_HANDOFF_REQUESTED, D2_KEYGUARD_HANDOFF_CONFIRMED, D2_KEYGUARD_HANDOFF_DENIED, D2_KEYGUARD_HANDOFF_UNAVAILABLE and D2_KEYGUARD_HANDOFF_TIMEOUT.

The Samsung hidden-method signature and runtime behavior require on-device verification; CI cannot validate the OEM hook.
