# D2 widget fix — October 15, 2026

Removed the 72-hour diagnostic text, count, timer and configuration option from the Home widget. Framework read-only diagnostics are unchanged.

Widget sizing now selects portrait/landscape launcher bounds rather than treating minimum height as the current height. Expanded widgets show SRP flags, check time and available hook statistics. Disabling the label now hides it.

Tapping SRP opens a visible status dialog and requests a fresh check, reporting success, failure or an already-running check. The background retains the configured lock action. Configuration now scrolls on smaller screens.

Validation: feature baseline and diff whitespace checks passed locally. APK build, lint, signing and packaging are delegated to the existing Android CI workflow; Samsung launcher sizing and tap behavior still require device verification. No reboot is required for this UI test.
