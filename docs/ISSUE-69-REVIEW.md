# Issue #69 development review — October 14, 2026

## Baseline and evidence

Continue Actions 38598510630 (build 1090), commit
db294a12d035827ce625fdfc79809f9ac4320df4. No newer successful build existed when
checked. The integration branch name still contains 1074; its source is build
1090 and this change retains that commit as its parent.

Issue #69 requests read-only strong-auth diagnostics and attributes the Dialer
exception to OneUIX Call.getHookConfigFromDexKit geoCodedLocation construction.
The supplied boot logs contain the D2 systemReady/wake registrations and
GUARDIAN_MINIMAL_LOADED in many scoped application and subsidiary processes.
Several diagnostic errors contain only stack frames, with their exception
messages omitted by filtering. The standalone boot check predates parts of the
automatic log's later reboot, so empty counters are not proof of missing hooks.
The service state shows a running foreground LockScreenService.

## Changes and boundaries

- Replace the previous conditional scheduler suppression with post-call
  observers. All calls proceed once with their original arguments and results.
  Separately display registration, callback counts, request reasons, current
  sampled flags and credential type. Validate sample schema, PID, boot count and
  elapsed timestamp; show sample age. Ignore legacy opt-out values.
- Remove the unused upstream bypass. Preserve OneUIX attribution and document
  its implementation without transplanting it. Samsung credentials, scheduling,
  flags and policy remain authoritative.
- Filter unsupported packages before OneUI preference reads; make the DMC lock
  lazy; restrict Guardian's loaded log to system_server/main SystemUI and
  presentation hooks to main SystemUI. Retain all supported Samsung app routes,
  including the screenshot process. LSPosed controls module injection; D2
  cannot prevent loading when a user selects excess scopes.
- Contain Dialer discovery within its protective try/catch and decline classes
  without a zero-argument constructor. Do not guess arguments or allocate
  uninitialized models. Unsupported location enhancement remains unavailable;
  this does not claim a fix for a separately installed upstream OneUIX APK.
- Track trusted active call notifications independently of usable controls.
  Accept non-ongoing ringing notifications with trusted fullscreen intent or
  CallStyle. Combine positive Telecom and notification evidence so an early
  false Telecom snapshot cannot hide a ringing call. Clear state on removal
  and listener disconnect/reconnect; exclude ordinary non-ongoing missed calls.
  The watchdog checks current call state centrally before reassertion.

## Outstanding device questions

The source does not establish the exact Samsung Android 17 scheduler signature
or real PIN/pattern timeout behavior. A missing signature is explicitly reported
as unavailable. Unknown/OEM reason bits are shown without guessing their policy.
The call-state changes address plausible races; the black-screen cause is not
proven by these filtered logs. Missing phone-state permission plus absent or
nonstandard call notifications can still leave call state unavailable. Listener
notification evidence may temporarily outlive Telecom state until removal.

Existing asynchronous wake signaling, boot handoff, bounded recovery, trusted
authentication, accessibility, launcher, UI and guarded power actions are
preserved. No new restart or system_server kill path is introduced.

## Licensing review

D2/OneUIX use AGPL-3.0; original contributor notices and dated adaptation notices
remain. Root and in-APK THIRD_PARTY_NOTICES match. Existing CI packages the exact
D2 commit's Corresponding Source, source revision/checksums, dependency report,
and pinned DexKit 2.2.0 source with recursive submodules. DexKit Core's LGPL-3.0,
other DexKit Apache-2.0, and library replacement instructions remain included.
Publish matching source and license/notice assets whenever distributing the APK.
Temporary Actions retention does not substitute for retained release sources.
This review does not certify every transitive dependency license; retain that
audit as a release requirement. GitHub repository description may still carry
historical all-rights-reserved text, although current LICENSE/README use AGPL.

See ONEUIX-PORT-VALIDATION.md for the device test matrix.
