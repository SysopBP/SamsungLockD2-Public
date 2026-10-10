# D2 0.6.3 Preview 1 setup
1. Install Kiosk-D2-Guardian-debug.apk normally over a compatible existing installation. A signing mismatch should be investigated rather than bypassed by deleting app data.
2. Open D2 and verify version 0.6.3-audit-repairs-v23.
3. Configure your independent D2 PIN and review the selected appearance and protection settings.
4. Grant optional feature permissions only for features you enable. Grant KernelSU access when using in-app Root Mode.
5. Test preview first, then explicitly arm real protection and verify normal PIN exit.
6. The KernelSU ZIP is optional and separate. Leave it disabled unless testing the bridge path. This bundle does not embed the APK in the module.
7. Test cold boot, normal D2 unlock, call return, screen-off/wake, kiosk release, USB rollback and sleep. Record results for this exact build before relying on the repairs.

D2 does not replace Samsung credentials. Preserve a working recovery route. Report device model, Android/One UI, root/LSPosed versions, exact app version, reproduction steps and diagnostics.
