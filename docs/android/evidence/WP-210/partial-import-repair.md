# WP-210 partial-import repair candidate

Issue: #387. Base: `2d1d19fb73adcad1e8ff725fb33a0c9b04dfee98`.
Reference: `db14559b39d32322b06477c6ae676112f583db50`.
Owner: `services-porter`. Session: `de168179112148fe9957f9e8b3519804`.

The maintainer selected a partial-import warning when cancellation or a local
save failure follows a confirmed device contact add. Caller cancellation
propagates; a local-save failure stops the import. Normal subsequent sync may
reconcile the local row. This is a bounded CM-10 repair, not full WP-210 or
issue acceptance.

The candidate adds a service-flow test for the device-add boundary, both
failure types, no later contact write and a retry that observes the unchanged
contact on the fake radio. App tests connect the actual settings state holder
to the real import service through a test adapter, fake radio and failing or
cancelled contact saver. They do not prove mounted production wiring or real
Room behavior. The teardown test separately checks cancellation of a parked
keep-alive and completion of an event subscriber; the original GC-dependent
case remains present.

The candidate also ports the 15 contact-share and 13 hashtag original vector
families to the feature-local implementations, with message normalization and
link-tokenization consumer coverage. The channel-sheet validator and sanitizer
pass those original ASCII inputs, but they accept non-ASCII letters or digits
that the shared utility removes or rejects. This discrepancy was reported to
the active WP-213 stream in #395; its product criterion and any shared edit
remain unresolved. These tests have not been independently established as
passing until the exact-candidate hosted job runs them with positive discovery.

Additional native tests distinguish an explicit stop of a parked keep-alive,
the quiet result of a domain cancellation during a tick, and propagation of
caller cancellation from a parked tick. They exercise the service with fake
session/store collaborators and check that its connected row is not marked
disconnected. Their exact-candidate execution remains pending.

The exact candidate's hosted CI result and log are authoritative for actual
test execution and discovery. The local preflight could not run Android tests:
`python tools/android-port/controller/ci.py preflight` reported missing explicit
`ANDROID_CI_STATE`, and `ci.py local-inputs` reported missing explicit
JDK/SDK/private-root paths. No device or hardware result is claimed.

CM-03's complete feature-consumer equivalence, CM-07's actual cancellation
outcomes, CM-09's real conversion/settings adapters, full CM-10 mounted and
real-store evidence, and CM-13's supported-runtime lifecycle evidence remain
open. The user's lifecycle decision and cancellation contract are recorded on
#387; this candidate alone does not establish those acceptance boundaries or
unlock any dependency.
