# Existing user API37 endpoint: boot ready, required profile unmet

**Historical guard-assessment snapshot.** Later user-authorized provenance
review establishes that2CPU/IME1 are broad-wrapper defaults, not requirements of
the selected original missing cases. See
[truthful4CPU/unchangedIME0 admission](shared-api37-profile-admission.md) and
[actual zero-start package-identity failure](device-shared37-integration.json).
The original observations and stop below are retained, not current blockers
or execution credit.

Following the coordinator's2026-10-19T13:45 handoff, read-only inspection
uses exactly
`C:\Users\camer\AppData\Local\Android\Sdk\platform-tools\adb.exe`,
host`127.0.0.1`, existing port5037, selected endpoint`emulator-5556`.
Before each query batch, the existing listener is verified present and its
owning process executable matches that exact binary. No server is started.
No API31 endpoint is queried.

| Read-only observation | Actual | Existing repair-cohort profile |
|---|---|---|
|ADB state|`device`, authorized|Authorized transport|
|SDK/release|37/17|37|
|`sys.boot_completed` / boot animation|1 / stopped|Boot complete|
|AVD|`Pixel_10_Pro_XL`|Same|
|System font scale|2.0|2.0|
|Window/transition/animator scales|0/0/0|0/0/0|
|Effective window size/density overrides|360x900 /160|360x900 /160|
|`getconf _NPROCESSORS_ONLN`|**4**|**2**|
|`settings get secure show_ime_with_hard_keyboard`|**0**|**1**|

The original cohort launcher's preparation command is
`wp503-prepare-native.py --api 37 --expected-cpus 2`. Its actual guest
CPU assertion requires2 before any preparation settings are written.
Preparation also unconditionally establishes
`show_ime_with_hard_keyboard=1` as part of the existing native profile.
The user explicitly prohibits implicit runtime-setting changes; the
observed0 is not silently converted to1. A four-CPU run must not be
relabeled the original two-CPU profile or inferred to be equivalent from
earlier diagnostic-only four-CPU evidence.

Therefore the previously missing API37 repair cohorts remain
**BLOCKED_BEFORE_LAUNCH on an observed runtime-profile mismatch**:
actual4 CPUs versus required2, and hard-keyboard IME0 versus required1.
Authorization and boot now succeed on this specific existing shared
endpoint; the historical private-emulator unauthorized result remains
historical, not a description of this endpoint. No300s retry or
authentication operation is needed or performed.

The relevant queries, after the listener ownership check, are:

```powershell
$adb = 'C:\Users\camer\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -H 127.0.0.1 -P 5037 -s emulator-5556 get-state
& $adb -H 127.0.0.1 -P 5037 -s emulator-5556 shell 'getprop ro.build.version.sdk; getprop ro.build.version.release; getprop sys.boot_completed; getprop init.svc.bootanim'
& $adb -H 127.0.0.1 -P 5037 -s emulator-5556 shell 'settings get system font_scale; settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale; wm size; wm density'
& $adb -H 127.0.0.1 -P 5037 -s emulator-5556 shell 'getconf _NPROCESSORS_ONLN; settings get secure show_ime_with_hard_keyboard'
& $adb -H 127.0.0.1 -P 5037 -s emulator-5556 emu avd name
```

This inspection performs **0 preparation/installation/instrumentation
operations and0 test discovery/starts/outcomes**. It does not clear logcat,
force-stop/reset/clear app or user data, write settings, manipulate keys,
restart shared ADB, launch/kill an emulator or create a private server.
The user's endpoint is left running and untouched by mutations.

No production import, source/API37 guard relaxation, new APK/build/CI,
successful API31 replay, physical or standalone acceptance occurs.
Retained composite source/APK bindings and exact f75 hosted failure/
missing-report diagnosis remain separate. Current published PR205 remains
f75/draft/failed; this readiness observation supplies no native test credit
to that publication or any retained composite.
