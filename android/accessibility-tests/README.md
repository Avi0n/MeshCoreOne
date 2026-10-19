# WP-503 native audit helpers

The existing App AndroidJUnitRunner hosts the native accessibility fixtures.
The declared session `wp503-prepare-native.py` and `wp503-run-native.py` entries
retain API31/37, font200, actual guest CPU verification, positive discovery and
the existing 300s boot/600s instrumentation limits. `--scenario launcher` expects
nine distinct starts and matching terminal outcomes; `--scenario full` expects80.
Do not retry failed cases until green or reduce these counts.

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
