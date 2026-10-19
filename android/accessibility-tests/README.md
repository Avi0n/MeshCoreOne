# WP-503 native audit helpers

The existing App AndroidJUnitRunner hosts the native accessibility fixtures.
The declared session `wp503-prepare-native.py` and `wp503-run-native.py` entries
retain API31/37, font200, actual guest CPU verification, positive discovery and
the existing 300s boot/600s instrumentation limits. `--scenario launcher` expects
nine distinct starts and matching terminal outcomes. The82-method inventory
requires three distinct cohorts: `feature-matrix`80, `platform-service-control`1
and `platform-callback-control`1. Each cohort requires its own fresh owned device
and instrumentation process; do not combine them into one package invocation.
The former `full` package selection is blocked, not relabeled as an80-case pass:
actual82-case runs retained historical Activities and correctly failed both
zero-Activity guards. Their failures remain evidence. `feature-matrix` explicitly
excludes only those two separately required classes with AndroidJUnitRunner's
`notClass` argument. No method, assertion or count is dropped from the inventory.
Do not retry failed cases until green or reduce these counts.

The existing private-adb adapter executes the three declared cohorts with fresh
scratch images per API/cohort and unchanged300s boot/600s instrumentation guards:

```powershell
python "$F\wp503-private-adb-native31.py" --phase matrixfeatures --head EXECUTED_HEAD --both-apis --scenario feature-matrix
python "$F\wp503-private-adb-native31.py" --phase matrixplatform --head EXECUTED_HEAD --both-apis --scenario platform-service-control
python "$F\wp503-private-adb-native31.py" --phase matrixcallbacks --head EXECUTED_HEAD --both-apis --scenario platform-callback-control
```

`$F` is the existing session-files directory. Record each actual source/APK
binding and80+1+1 unique identities per API; never substitute a prior-source
control or a full-context positive for standalone cold-start evidence.

`DeviceAccessibilityHarness` awaits the real `MeshCoreApplication.container`
startup contract on the instrumentation thread before feature setup or service
requests. Public in-window stack/Looper observations found real process graph
construction occupying the main thread while client-state messages and the
heartbeat queued overdue. Existing launcher bodies already await this same
container, but formerly only after requesting touch exploration. Awaiting the
actual prerequisite before that request changes ordering, not a deadline: no
sleep, synthetic readiness, Compose-clock advancement, extra resources or
production change is introduced. Startup exceptions propagate; the existing600s
instrumentation guard still bounds execution. This audits process-ready feature
flows, not cold-start latency or touch-exploration delivery during initialization.
The independent cold platform controls do not inherit this prerequisite; all
their recorded failures remain.

`--scenario platform-service-control` selects one new
`NativePlatformServiceControlTest` method. Unlike the preceding service control,
it has no Activity/Compose rule or test dispatcher. It verifies zero initial
Activities and observes lifecycle callbacks throughout the control. Actual
manager state, acknowledged flags, original1000ms activation/restoration windows,
late-success rejection and public snapshots remain. The original80 test bodies,
helpers and assertions are unchanged. This isolates another harness confound;
a passing control cannot clear historical feature failures or prove their cause.
That control introduced inventory81; no full81 invocation occurred. The
additional callback diagnostic below expands the current inventory again.

`--scenario platform-callback-control` selects one new
`NativePlatformCallbackDeliveryTest` method. It retains the same real manager,
acknowledged flags and original1000ms activation/restoration predicates, while
registering a public touch-exploration listener and sampling actual main-looper
progress through nonblocking, at-most-one-pending heartbeats every100ms.
Heartbeat times, public callback times and a post-timeout public Java thread
stack are diagnostic observations, never readiness substitutes. When a main
heartbeat remains pending100ms, one public Java stack/Looper.dump sample per
phase records actual queue/dispatch state, collection duration and whether the
heartbeat remains pending. Collection time counts against the unchanged1000ms
window; a probe can affect timing and is not a causal readiness fix. No Activity,
Compose rule, test dispatcher, fake clock or service-state writes beyond the
original request/restoration exist. All preceding81 methods/helpers remain
unchanged. This is a different diagnostic treatment, not a retry or causal fix
of their failures. The inventory becomes82; acceptance requires all three
cohorts above, not a shared-context package pass.

`NativeLauncherFocusAccessibilityTest` samples AccessibilityManager state through
the actual main looper, where Android applies its binder-delivered state updates.
The same predicates and original1000ms activation/restoration deadline remain;
time spent waiting for the main looper counts, and a late true sample fails.
Main-thread sampling is not a service readiness guarantee or physical TalkBack.
On timeout, the original exception remains primary while the existing public
system/client snapshot collector runs as a close operation. Snapshot failures
are suppressed onto that exception, not substituted for it. Collection is
explicitly after the original deadline: late state changes never earn a pass.

## Owned emulator teardown

After the owned emulator process has exited successfully, wait for its **specific**
adb transport to disappear before removing its exact scratch image paths:

```powershell
python android\accessibility-tests\native_device_cleanup.py --adb C:\android-sdk\platform-tools\adb.exe --serial emulator-5714
```

The observation is bounded to10s, includes adb command time, requires the actual
transport-list header and rejects late success, command errors, malformed output
and duplicates. Another user's device does not have to disappear. This only
observes; it does not stop devices, restart adb, or change instrumentation limits.
The prior immediate-empty assertion failure remains historical evidence.

Deterministic helper tests, with positive discovery:

```powershell
python -m unittest discover -s android\accessibility-tests -p test_native_device_cleanup.py -v
```

Device-run identities, commands, APK/source bindings, actual results and remaining
gates belong in `docs\android\evidence\WP-503\`, not in a second hosted-CI bundle.
