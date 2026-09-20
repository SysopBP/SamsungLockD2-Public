# D2 0.4.2 paired bridge for KernelSU Next

Install SamsungLockD2-KSUNext-v0.5.4-paired.zip via KernelSU Next Modules, then reboot. Module ID samsunglock37_ksunext is retained; version code 54 supersedes 53.

The ZIP bundles SamsungLockD2-v0.4.2-paired-calls.apk. An existing D2 installation and its data are preserved: install the APK manually to update it. The module installs the bundled APK only if D2 is absent. Same-signed paired updates preserve PIN/settings; do not uninstall on a signature mismatch.

Enable Show D2 when the screen wakes and, for root-assisted wake launching, Optional KernelSU root mode. Grant D2 superuser access. These options are not enabled automatically. The module starts the opted-in foreground wake service after boot; it does not intercept launcher gestures.

D2 0.4.2 adds phone UI access during kiosk, notification-based call controls, and compact live notification banners. Grant D2 notification access for call controls and live messages. Banners respect notification privacy settings; message apps open only after the D2 PIN. Phone-app screens can be accessible while kiosk is active because Android allowlists whole packages. Home/Recents remain restricted.

The bridge shell scripts are unchanged. This update needs physical Samsung/KernelSU retesting; see docs/CALLS-AND-LIVE-NOTIFICATIONS.md in the source archive.
