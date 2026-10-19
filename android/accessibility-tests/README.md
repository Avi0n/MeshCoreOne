# WP-503 native audit helpers

## Focused production collation parity

`NativeLocalizedCompareParityTest` runs the retained independent Swift vectors
from WP-316's `oracle-output.txt` and `SwiftTextParityTest` on actual API31/37,
not host-JDK or copied collation logic. Its ten vector methods require both
production equality directions and both production comparator zero/nonzero
results at Locale.US; one additional method requires stable ordering for all
seven equal pairs in both input orders. Test-only reflection loads the actual
installed target class through its target classloader and invokes the public
JVM ABI of the Kotlin-internal implementation. There is no production
visibility/API change, fake collator, normalization or fallback in the audit.

The existing runner's focused `localized-compare` selector expects exactly11
distinct starts/matching outcomes on each fresh owned API31/37 profile:

```powershell
python "$F\wp503-private-adb-native31.py" --phase localizedcompare --head EXECUTED_HEAD --both-apis --scenario localized-compare
```

This focused cohort has no Activity/Compose rule or service readiness guard.
It proves only the retained comparison vectors and stable equal ordering,
not native user-flow, physical TalkBack or whole-WP acceptance. The earlier
83 inventory and every negative stay bound to their earlier source/APKs;
this addition is not an84/94-case full-suite result. Broad `feature-matrix`
selection must exclude this independently selected audit class.

## Existing feature and service cohorts

The existing App AndroidJUnitRunner hosts the native accessibility fixtures.
The declared session `wp503-prepare-native.py` and `wp503-run-native.py` entries
retain API31/37, font200, actual guest CPU verification, positive discovery and
the existing 300s boot/600s instrumentation limits. `--scenario launcher` expects
nine distinct starts and matching terminal outcomes, explicitly excluding the
separately required controlled-cleanup method. The83-method runnable inventory
requires four distinct cohorts: `feature-matrix`80, `platform-service-control`1,
`platform-callback-control`1 and `cleanup-preservation`1. Each requires its own fresh owned device
and instrumentation process; do not combine them into one package invocation.
The former `full` package selection is blocked, not relabeled as an80-case pass:
actual82-case runs retained historical Activities and correctly failed both
zero-Activity guards. Their failures remain evidence. `feature-matrix` explicitly
excludes only those two separately required classes with AndroidJUnitRunner's
`notClass` argument, plus the separately required fault-injection method by its
class#method identity. No method, assertion or count is dropped from the inventory.
Do not retry failed cases until green or reduce these counts.

The existing private-adb adapter executes the four declared cohorts with fresh
scratch images per API/cohort and unchanged300s boot/600s instrumentation guards:

```powershell
python "$F\wp503-private-adb-native31.py" --phase cleanupfeatures --head EXECUTED_HEAD --both-apis --scenario feature-matrix
python "$F\wp503-private-adb-native31.py" --phase cleanupplatform --head EXECUTED_HEAD --both-apis --scenario platform-service-control
python "$F\wp503-private-adb-native31.py" --phase cleanupcallbacks --head EXECUTED_HEAD --both-apis --scenario platform-callback-control
python "$F\wp503-private-adb-native31.py" --phase isolatedcleanup --head EXECUTED_HEAD --both-apis --scenario cleanup-preservation
```

`$F` is the existing session-files directory. Record each actual source/APK
binding and80+1+1+1 unique identities per API; never substitute a prior-source
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
of their failures. That82 inventory requires its three compatible cohorts,
not a shared-context package pass. The additional controlled
cleanup regression below brings the current runnable inventory to83.

`NativeLauncherFocusAccessibilityTest` samples AccessibilityManager state through
the actual main looper, where Android applies its binder-delivered state updates.
The same predicates and original1000ms activation/restoration deadline remain;
time spent waiting for the main looper counts, and a late true sample fails.
Main-thread sampling is not a service readiness guarantee or physical TalkBack.
On timeout, the original exception remains primary while the existing public
system/client snapshot collector runs as a close operation. Snapshot failures
are suppressed onto that exception, not substituted for it. Collection is
explicitly after the original deadline: late state changes never earn a pass.

## Controlled primary/cleanup failure regression

The additional launcher-class method explicitly injects a primary AssertionError after
actual activation and a bounded1500ms real-main latch stall. The unchanged
restoration waiter must fail its original1000ms deadline, even if its first
late sample observes the desired state. The same primary object must escape,
with exactly the actual ComposeTimeoutException suppressed. Real original
flags/client state must be restored after the failed window; that safety
observation is not a cleanup-deadline pass. The main latch releases on every
exit. No original82 method body, waiter, deadline, product or237/238 assertion
changes. This is explicit harness fault injection, not a naturally occurring
dual failure, physical TalkBack observation or new user-flow acceptance.

The initial mixed launcher10 invocation atb7 is retained:31 10PASS37.083s,
37 9PASS/1FAIL89.394s, original motion-flow activationfalse87 checks1001ms.
The controlled preservation method itself passes both. This does not establish
that the fault caused the later activation failure; neither may it be treated
as an unperturbed feature-only invocation.

`--scenario launcher` therefore selects the original9 only, using the public
AndroidJUnitRunner class#method exclusion; `cleanup-preservation` explicitly
selects the one fault method on its own fresh device/process. Do not mix an
intentional real-main fault into normal feature acceptance. Earlier82-case
reconciliation stays bound to its original source/APKs; it is not a current83
run. Do not report full83 acceptance without all four compatible current-source
cohorts and exact identity/input reconciliation.

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
