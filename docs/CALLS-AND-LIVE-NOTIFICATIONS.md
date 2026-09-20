# D2 0.4.2: calls and live notifications

This update addresses the report that incoming calls could not be answered during D2 root kiosk on a Samsung Galaxy S26 Ultra running Android 17. The previous kiosk allowlist contained only D2, and the notification listener discarded ongoing notifications, including phone calls.

## Calls

- The temporary kiosk allowlist now includes the selected Phone app, the system dialer, and system in-call UI services. Samsung's separate system in-call package is supported.
- Home/Recents remain disabled. Answering a call does not release kiosk, accept the D2 PIN, or mark Galaxy Island as unlocked.
- With D2 notification access enabled, D2 shows the Phone app's available call controls and an **Open phone call** button. Ongoing call notifications are retained. Controls that the Phone app marks as requiring authentication are not offered by D2.
- Call controls use the Phone app's own pending intents. D2 does not become the default dialer or request SMS, contacts, call-log, or call-answer permissions.
- The wake service avoids covering a call while an actionable phone notification is present.
- Android's allowlist works at package level: other screens within the allowed Phone apps can also be accessible without the D2 PIN. Unrelated apps are not allowlisted. This remains an experimental privacy screen, not a replacement for Android secure keyguard.

## Live notifications

**Live notification banners while D2 is locked** is enabled by default. New or changed messages appear in a compact three-line banner for eight seconds while D2 is visible, including repeated message text posted again by a messaging app. The notification list also updates live. These are visual alerts: D2 does not play extra sounds, vibrate, or wake the display for messages. Android kiosk mode or Do Not Disturb may suppress the messaging app's normal sound/vibration.

Banners follow the existing privacy choice: Hide all suppresses them, Count only says “New notification,” App names hides sender/text, Public only shows only public content, and All previews permits private text. Secret notifications never appear. Tap a banner or notification card to enter the D2 PIN before its app opens. Galaxy Island remains hidden while D2 is locked.

## Install and verify

Exit kiosk with your PIN, install the same-signed paired D2 0.4.2 APK over the previous paired build, and lock D2 again. No Galaxy Island update is required. If Android reports a signature mismatch, stop; uninstalling clears your PIN and settings.

In D2, enable **Grant notification and media access**. The optional KernelSU Next bridge 0.5.4 bundles this APK for fresh installations; it preserves an existing installed APK, so updating only the module does not update an existing D2 installation.

On the physical phone, test an incoming call while kiosk is active: answer, end, decline, and miss a call; verify D2 remains locked afterward and Home/Recents stay restricted. Send an SMS with App names, All previews, and Hide all selected in turn, then confirm that opening its app requests the D2 PIN. Samsung Android 17 behavior for this new fix still needs that retest; previous hardware testing does not validate the new code.

