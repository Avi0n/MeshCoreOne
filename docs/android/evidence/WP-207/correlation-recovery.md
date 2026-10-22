# WP-207 correlation recovery successor

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `services-porter`.
Base/trusted main: `0a7a84e388393557e4b66ac7fd322248e28611fd`.
Source pin: `db14559b39d32322b06477c6ae676112f583db50` (unchanged).
Manifest semantic digest:
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Policy revision:
`70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
Managed branch: `cbattlegear-psychic-invention`; actual project session/attempt:
`c7207c38-1af6-491f-87f8-08f11418c5ea`.
The successor PR's current head identifies implementation bytes; this document
is not a second reproducible CI-result bundle or an acceptance receipt.

The trusted reservation CLI acquired the real WP-207 advisory reservation,
revision 1, with `wp-owned`/`modify` for runtime, its deviation and its evidence.
No protocol, messaging, App, shared policy or build/dependency edits were admitted
or made. The original prerequisite implementation merge commits for WP-107
(`#19`), WP-202 (`#23`), WP-204 (`#21`) and WP-205 (`#18`) are ancestors of base.
Diagnosis context is existing issue #261, not a claim to close its remaining
cross-WP integration work.

## Behavior and acceptance

`WP-207-behavior`: the existing public correlation diagnostic now drives a real
generation-owned physical close and the normal retry policy, rather than an
App-only workaround or a protocol-quarantine bypass.

`WP-207-boundaries`: ten deterministic real-session regressions assert timeout,
quarantine, DISCONNECTED/token withdrawal, single physical/service close,
fresh-generation READY, exact state ordering, diagnostic coalescing, stale/late
isolation, manual stop, cancellation and WiFi cooldown. These are authored
assertions until the exact candidate's hosted job proves execution.

`WP-207-source-test-parity`: no source family or original assertion is removed.
The source-derived watchdog/retry policies stay unchanged; the native recovery
event and dispatch boundary are documented in deviation A-08.

## Commands and execution limits

- `python .\tools\android-port\controller\validate.py`: passed; pinned reference
  and manifest valid (1,866 reference inputs, 64 WPs, 178 dependency edges).
- `python .\tools\android-port\portmap.py`: passed; traceability only, not parity.
- `git diff --check`: passed.
- `python .\tools\android-port\controller\ci.py preflight`: blocked, exit 2;
  explicit `--state`/`ANDROID_CI_STATE` was not supplied by this execution host.
- `python .\tools\android-port\local\check.py --stages preflight`: blocked,
  exit 1, `Malformed installed reservation runtime manifest`, before Gradle
  execution. The runtime was not replaced and the native lock was not bypassed.

The declared relevant module command is `:core:runtime:test`. The candidate's
normal hosted workflow supplies readiness, strict dependency verification and
the actual discovered/executed test results. No local Kotlin test count or pass
is claimed. Feature publication is explicitly unverified pending that run;
hosted exact-head job/log remains authoritative without retaining another CI
result bundle here.

WP-503's existing native execution reservation remains untouched. No emulator,
hardware, signing, release, settings, schedules or repository merge action was
performed. Merge remains serialized behind the coordinator's native END/cleanup.

WP-208 owns persistent per-envelope channel-query failure progress across graph
replacement; WP-303 owns its App wiring. Runtime requires no new producer signal
or App lifetime contract for this repair.
