# Automatic root-only DMC recovery

D2 automatically targets these Samsung DMC vault states:

| Feature | Target |
| --- | --- |
| Screen-lock flag | Disabled |
| Maintenance Mode flag | Disabled |
| Download Mode AT Command (`AT+SUDDLMOD`) flag | Enabled |
| Download Mode eligibility derived from DMC flags | Allowed |

There is no enable switch or Fix Now requirement. Checks run on D2's early
`BOOT_READY` signal, locked/normal boot broadcasts, app updates, Guardian service
creation, and app opening/resuming. Existing Guardian boot launch and token
confirmation run independently. A short-lived helper runs only through `su`,
requires UID 0, and supports the primary user. Shizuku/UID 1000 is not a fallback.
Root must already be granted by the root manager; D2 cannot grant itself root.

Failed checks have three bounded retries after 5, 15, and 30 seconds while the app
process remains alive. Subsequent startup events can try again. Installing a new
app does not itself deliver a boot broadcast, and Android suppresses broadcasts
to a never-opened/force-stopped app; open D2 once after initial installation.

The **Automatic DMC Recovery → View feature status** dialog shows all four
observed states, targets, verification result, and check time. It updates while
open. Unknown, denied, unsupported, and timed-out states never count as verified.
Stored status is scoped to Android's boot count.

## Backend and limits

The supplied `JBCompanion_1.0_src.apk` reads Samsung
`VaultKeeperManager.getInstance("DMC").read(1)`. It expects a 32-byte record and
labels bytes 0, 1, and 2 as screen lock, Maintenance Mode, and AT-command flags.
Its Fix Now/boot action changes only byte 2 to 1. D2's independently implemented
policy changes the requested three flags to 0, 0, 1, preserves the other 29
bytes, validates the record and boolean flag values, and requires successful
write plus a full matching read-back. Correct records are not rewritten.

Download Mode status follows JBCompanion's flag-derived rule; it is not a
physical bootloader/Odin test or a separate fourth writable flag. The helper
does not execute the AT command or reboot into Download Mode. It does not erase
Android passwords, PINs, patterns, or biometric enrollment, and it does not
start/stop the actual Samsung Maintenance Mode session. The panel reports the
corresponding DMC vault flags, separately from D2 authentication.

Root does not guarantee Samsung VaultKeeper will authorize the caller's UID,
package, or SELinux domain. Such failures are visible and do not trigger an
injection, UID impersonation, service patch, or credential-removal workaround.
Real Samsung firmware validation is required before claiming device support.

## Validation

Unit tests cover preserving unrelated vault bytes, idempotence, malformed and
unknown records, write rejection, ineffective writes, read-back mismatch, and
all eight combinations of the three DMC flags. An emulator cannot validate
Samsung VaultKeeper permissions or bootloader eligibility.
