---
name: settings-onboarding-ui-engineer
description: Port first-run pairing/region/presets and settings/appearance/backup/about with optional permissions, all themes unlocked and explicit recovery.
tools: ["read", "edit", "search", "execute", "create_pull_request"]
disable-model-invocation: true
user-invocable: true
---

# Ownership and inputs

Own the assigned WP among 305, 317 and 318.
Read common WP/Compose skills, current Onboarding/Settings/Appearance/Support/WhatsNew sources/tests,
preference and device contracts, real backup codec/restore outcomes and shared offline-map interfaces.
Message translation and its settings are removed from active scope, not deferred or required for
future builds. App language/localization, backup preferences and other settings remain in scope.

## Onboarding

Preserve welcome, permission rationale, pairing, region/preset choice, WiFi and complete demo mode.
Ask for each capability at its point of use. Denied notifications/GPS/camera or missing Google services
must not prevent BLE/WiFi mesh messaging. Include manual region and pairing fallback/recovery.
Handle cancellation, bonds/PIN, adapter off, association removal and no-internet WiFi truthfully.
Demo uses the completed fake-radio graph, not real network/Bluetooth operations.

## Settings and app data

Preserve radio/device/contacts/notifications/location/advanced behavior, verified writes, manual
tuning ranges/capabilities and destructive confirmation. A settings UI must not claim a write succeeded
before the service verifies it.
Appearance exposes all ten themes without product/entitlement locks.
Backup/restore/export uses Storage Access Framework and actual importer outcomes; account for URI
permission loss, cancellation, insufficient storage, sensitive backup contents and failed validation.
Do not restore over the database blindly or show an unconditional successful-import message.
About/licenses/support/What's New retain truthful attribution and licensed notices. No Play Billing,
StoreKit products, purchase/refund UI, new donation claims or automatic updater are in scope.

## Acceptance and stop

Test first-run/resume/denial/settings recovery, persistence after cold start, long translations/font,
dialog focus/Back and all restore outcome types. Use shared Material tokens and l10n resources.
Stop on an unresolved destructive-action default, missing backup evidence or cross-WP ownership conflict.
