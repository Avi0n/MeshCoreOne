# WP-204 implementation and verification record

## Current DATA-20 reconciliation (#381)

The executing vanity-search cancellation repair is already merged in
[#345](https://github.com/cbattlegear/MeshCoreOne-Android/pull/345).
Its exact-head hosted verification discovered and passed both key-generation
suites, including executing-search, pre-start and entropy-thrown cancellation.
The relevant production and test files remain unchanged at the reconciliation
base. See the [current DATA-20 record](active-key-search-cancellation.md#current-reconciliation-381)
for immutable identities, assertion boundaries and the local execution limitation.
No new production repair or broader WP-204 acceptance is claimed.

## Historical implementation record

The record below preserves its original candidates, counts and pending work.
Its status and execution limitations are not a current DATA-20 verdict.

**Status: actual owned unit/source verification passed131/131 after both
independently reviewed source repairs. Shared checksums/consumer locks and
module-local test SDK locks are admitted/generated. Historical headcc02 has
genuine two-host root/APK proof; the repaired current3da-based head still
requires fresh normal same-head root/APK/protocol verification. Neither the
historical113 tests nor the historical APK closes the two source defects.**

Repository `cbattlegear/MeshCoreOne-Android`; owner `data-persistence-engineer`.
ACTIVE coordinator receipt `autonomous-WP-204-dc15f1ba`; app session
`00cbcd6f-1b3b-4e40-8d44-2ce4216eb72e`; native project alias
`13956b48-f450-42b5-8c63-778fae11425d`; project
`663db92c-ed50-4a77-aded-bda85a7c503a`; branch
`cbattlegear-upgraded-carnival`. Initial verified clean HEAD and actual main:
`dc15f1ba445acf3230383ea68d4827c592f3fafa`.
Final coordinator-verified integration base:
`3da3a73b8481c49d035486924a813b331bf184d0`, actual guarded Session merge
after the actual PR20 repair at`fbb7eb6f88f1b3a74eaddb68cac911ff52650d02`. Only the
owning branch was reconciled; original lease/base and failed attempts remain
unchanged. Rewritten branch publication uses the explicit old remote-tip
lease `ef903325391dfe8a0d0ec74d85b44c96df802199`.
The second reconciliation onto actual3da uses exact owning old remote tip
`cc02f7cb8ed7ab41ec70951c033ffecabd9b3297`; no peer/main checkout was changed.
The **native CLI/app alias is `00cbcd6f-1b3b-4e40-8d44-2ce4216eb72e`**;
the separate project alias above is not a replacement for that lease identity.

Read-only source `db14559b39d32322b06477c6ae676112f583db50`, tree
`8918fdc604341e6996a68c88f6bb1c02b9c2f87e`; semantic manifest
`78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746`;
combined trusted policy revision
`56bdc53548bc86d631245795dfa38b4fc86048e0e7cbe1c7d5695879b035b42a`.
Actual merged003/102/201 ancestry and all8 source blobs were checked.
No reference, source case catalog, shared schema/contract, workflow, catalog,
app manifest or protected verification policy changed.

## Source/API/error map

| Owned source | Native surface | Original declaration evidence |
| --- | --- | --- |
| `MC1/Models/DevicePreferenceStore.swift` | DevicePreferenceStore, GPSSource, literal uppercase-UUID keys, immutable device snapshots; real shared DataStore reads/updates/observation/reset | DevicePreferenceSourceTest has all7 original cases; native boundary cases cover unknown/present GPS, reopen and reset separation |
| `MC1Services/.../Services/AppStorageKey.swift` | All48 AppStorageKey definitions, PreferenceValue/PreferenceSnapshot/PreferenceEditor, PreferenceStore, notification/appearance consumers and backup presence seam | Literal/raw/default/type/cold-start/reset/ordered-array/source-policy boundary assertions; no arbitrary replacement settings |
| `MC1Services/.../Services/SceneStorageKey.swift` | SceneStorageKey.MAP_CAMERA_REGION and ScenePreferenceStore, per-scene persisted default-empty string | Separate scenes, reopen, reset and exclusion from manual app-preference snapshot |
| `MC1Services/.../Services/KeyGenerationService.swift` | KeyGenerationService, SecureSeedSource, GeneratedIdentity, expanded import validation/public matching; four source failure families plus native InvalidPrefix guard | KeyGenerationSourceTest has all25 original declarations/32 parameter-expanded scenarios; independent RFC8032 seed/public and single SHA512 expansion assertion |
| `MC1Services/.../Services/KeychainService.swift` | SecretStore password store/retrieve/delete/has, source service/base64 account, stable radio partition, AndroidKeystoreCryptography and real FrameworkKeystoreKeyAccess | Actual DataStore/JCA boundary tests declare namespace, typed availability/loss/invalidation/authentication/version/IO, nonce/AAD, concurrent creation, rotation/deletion/cancellation and durability assertions |
| `MC1Services/Tests/.../PersistenceKeysThemeTests.swift` | PersistenceKeysSourceTest consumes the actually merged model constants and datastore appearance definitions | Both2 exact bare-name/no-prefix cases |
| `MC1Services/Tests/.../Services/KeyGenerationServiceTests.swift` | KeyGenerationSourceTest | All25 source identities, both three-row vanity families and four-row invalid-length family; none excluded |
| `MC1Tests/Models/DevicePreferenceStoreTests.swift` | DevicePreferenceSourceTest | All7 source identities, none excluded |

Every production/test Kotlin file has an owned pinned PortedFrom or AndroidOnly
disposition. Native tests currently declare the34 original cases and41 source
parameter scenarios; **declarations do not count as execution**.
[`collect_evidence.py`](collect_evidence.py) validates all8 inputs/blobs, the
trusted WP-004 literal family rows and complete actual native JUnit set.
Missing/malformed/zero/failed/skipped XML fails; it does not produce a
`local-evidence.json` pass while native execution is absent.
There are now **113 actual discovered/passed native methods**, one more than the112-method
checkpoint: the added owner-close cancellation case prevents a cancelled close
from leaving a permanently registered closed DataStore owner.
All113 execute with0 failures/errors/skips. One accidentally nested concurrency
method was absent from the first112-case runner output; the collector rejected
that mismatch, the method was moved into the actual JUnit class, and its
assertions now execute. No expectation or coverage count was weakened.
Complete verbatim local XML is retained in [`local-junit/`](local-junit/).
Native input hashes and whether those inputs match the observed Git HEAD are
recorded; local execution during uncommitted implementation is not mislabeled
as immutable hosted same-head authority.

`StorageFailure` distinguishes first-unlock, current lock, inaccessible user
storage, permission, IO, preferences/state/envelope corruption, GCM
authentication, missing formerly-used key, permanently invalidated key, corrupt
native key type, provider failure, unsupported version, oversize, owner lifetime
and input errors. `StorageOperation` preserves read/write/delete/rotation/export
context and causes. `StoreState` exposes loading/ready/failure without durable
empty replacement. Only absent secret entries return null/false.

## Actual commands and results so far

Commands run from the owning repository worktree. Private absolute toolchain/
cache paths are intentionally not committed; the actual provisioner's own
environment.json and logs remain in this session's artifact directory.

| Actual command/task arguments | Result |
| --- | --- |
| `python tools\android-port\controller\ci.py preflight` before provisioning | Failed exit2: missing explicitly supplied state; no build started |
| `android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 512m -GradleArguments @(':core:datastore:testDebugUnitTest','--dependency-verification','strict')` before environment setup | Failed exit1: JAVA_HOME/SDK absent; no Gradle/test started |
| `python tools\android-port\controller\ci.py provision --root <own-private-session-directory> --accept-sdk-license` | Passed exit0: exact first-party pinned JDK/SDK archive checksums/sizes, unchanged pinned SDK terms and extraction-only installation |
| `python tools\android-port\controller\ci.py preflight --state <own-provisioner-environment.json> --output <own-private-evidence> --local` | Passed exit0: actual JDK21.0.12.1+1, platform37.2r1/build-tools37, private caches and unchanged credential allowlist |
| `:core:datastore:compileDebugKotlin --dependency-verification strict --no-build-cache --project-cache-dir <own-private-cache> --quiet`, bounded512MiB launcher | Failed exit1 after3m31s: missing owned module androidApis strict lock state |
| Same exact compile with `--write-locks`, still `--dependency-verification strict`, bounded512MiB launcher | Failed exit1 after1m19s:18 new DataStore1.2.1/Okio3.9.1 artifacts lack root checksums; Kotlin/tests did not execute |
| `python tools\android-port\controller\validate.py` | Passed exit0: frozen ownership/reference,65 WPs/185 edges/eight human gates |
| `python tools\android-port\controller\verification_config.py --check` | Passed exit0: unchanged foundation overlays; feature formal verification remains unconfigured |
| `python tools\android-port\controller\runtime_inputs.py` | Passed exit0: all77 required committed runtime inputs/checkout bytes matched initial base |
| `python tools\android-port\portmap.py` | Passed exit0: real provenance validator; headers are not feature acceptance |
| `python docs\android\evidence\WP-204\collect_evidence.py` | Correctly BLOCKED exit2: actual testDebugUnitTest XML absent; no synthetic passed artifact written |
| `git diff --check` | Passed; no shared path modifications observed |
| `python docs\android\evidence\WP-204\dependency_publications.py --write --write-notices --check-notices` | Passed actual18 module/POM publications, the existing serialization1.7.3 BOM and10 downloaded binary checksum/size checks; generated five exact dependency notice/copyright assets within the module plus exact app-path amendment, not legal/build admission |
| `:app:dependencies --configuration debugRuntimeClasspath :core:data:dependencies --configuration debugRuntimeClasspath --dependency-verification strict --no-build-cache --quiet`,512MiB/private raw capture | Command completed exit0, but actual graphs are **BLOCKED** with16/19 FAILED components; complete raw output retained, never counted as successful runtime resolution |
| Same exact app/data dependency reports for `debugCompileClasspath` | Command completed exit0, no reported component failure and no new DataStore/Okio transitive compile component; not a compile/build/test pass |
| `python docs\android\evidence\WP-204\collect_lock_admission.py --write --runtime-report <actual-private-runtime-log> --compile-report <actual-private-compile-log>` | Passed immutable existing lock input/hash/configuration record checks and retained complete raw reports; no replacement lock was manufactured |
| `python docs\android\evidence\WP-204\inspect_packaging.py` before actual assembly/environment | Correctly BLOCKED exit2; no APK/config/hardware result created |
| `python docs\android\evidence\WP-204\admit_dependencies.py --write` | Passed exact active amendment:41 vetted new artifact SHA256 entries; all incumbent/config and6 frozen WP-205 SDK entries preserved; canonical-LF XML280031bytes/SHA256`7e80299379771ef1ba8f7c4b497387c81b22ac5052fc0f667aefb1b2937ca09f` |
| `python -m unittest discover -s android\scaffold -p test_verification_metadata.py -q` | Passed11 actual existing verification-metadata assertions |
| `:core:datastore:testDebugUnitTest --write-locks --dependency-verification strict --no-build-cache --quiet`,512MiB/private launcher | First reached real compilation and failed on a nonpublic Context API; fixed with guarded normal applicationContext. Retry passed112 cases, but owned source collector rejected113 declarations/112 execution because of the nested method; repaired rather than waived |
| `:core:datastore:verifyPreferenceTests --dependency-verification strict --no-build-cache --quiet`,512MiB/private launcher | Passed113 discovered/passed,0 failed/errors/skips; actual34 original declarations/41 parameter scenarios accounted |
| `:core:datastore:resolvePreferenceDependencies --write-locks --dependency-verification strict --no-build-cache --quiet`,512MiB/private launcher | Passed exact owned graph plus8 admitted consumer configurations per module. Actual changes only7 app/8 core:data runtime/lint configurations;16/19 new component records, no compile/unrelated lock-state changes |
| `:core:datastore:lintDebug :core:datastore:verifyPreferencePackaging --dependency-verification strict --no-build-cache --quiet`,512MiB/private launcher | Actual owned lint XML has0 errors/1 UseTomlInstead warning; combined build reached D8 but heap and512MiB metaspace exhausted. Only that owning command tree was stopped; no APK produced |
| Separate `:core:datastore:verifyPreferencePackaging` in a fresh512MiB private JVM, same strict flags | Failed exit1 after1m5s at actual`:app:mergeExtDexDebug`: Java heap space/D8DexArchiveMergerException. No APK/config/native/notice packaging proof inferred |
| On actual mergedfbb base, `:core:datastore:verifyPreferenceTests :core:datastore:resolvePreferenceDependencies --dependency-verification strict --no-build-cache --quiet`, one private512MiB JVM | Passed113 real cases/34 originals/41 parameter scenarios and all admitted strict selected graphs; no APK task or ignored suite substituted |
| `python docs\android\evidence\WP-204\verify_notices.py` immediately after Windows rebase | Correctly failed exit2: app notice checkout had CRLF1458bytes instead of actual publisher1434 |
| Same verifier with `--normalize`, then check-only again | Passed: only the specifically admitted app notice was normalized after canonical-LF bytes matched the independent publisher receipt. Four module notices retain exact bytes with scoped `-text`; no license terms, global Git config or unrelated app asset changed |
| Normal scaffold run37218314995 attempt1 on head`afa132b54fd3b1fa1f391b0628b813502b9977ff` | Both hosts reached real APK assembly but owned packaging verification failed on an assumed legacy AAPT dump shape. Complete failed Linux/Windows commands are retained, not accepted as root/APK evidence |
| `python -m unittest discover -s docs\android\evidence\WP-204 -p test_packaging_parser.py -v`, plus parser against actual approved PR20 APK dump | Passed8 positive/negative format assertions; actual SDK37.0.0 uses full Android namespace URI, literal`false`, unqualified`xml/...` resource name and UTF8. Parser fixed to observed shapes without relaxing backup/domain/reference checks; baseline diagnostic is not candidate APK acceptance |

Gradle uses its actual checksum-pinned9.8.0 wrapper and AGP9.4.1 built-in Android
Kotlin, existing2.3.20 compiler/coroutines1.10.2/BC1.86, one worker/in-process
Kotlin,512MiB build heap/512MiB metaspace and256MiB test heap/metaspace,
SerialGC/two CPUs. Provisioning used only the trusted pinned archives; no
unversioned SDK CLI/helper bootstrap, global installation/cache/credentials,
third-party source upload, checksum weakening or signing operation occurred.

## Concrete remaining admission and execution

The initial receipt leases `android/core/datastore/`, this evidence subtree and
`docs/android/deviations/WP-204.md`; the later amendment adds only the exact
app BSD notice path below. New DataStore is absent from the actual
merged catalog/verification metadata. The module uses an independently
inspected exact1.2.1 coordinate without editing the shared catalog.
[`dependency-admission.json`](dependency-admission.json) records actual strict
failure artifact identities and independently checked publisher SHA256 values.

Root `android/gradle/verification-metadata.xml` is now exclusively leased to
WP-204 through the concrete serialized amendment. The frozen WP-205
XML-only handoff was checked by actual Git blob/complete SHA256/size and by
its actual PR comment; all6 SDK32/33/37 JAR/POM entries are retained. Its
serialized admission covers actual DataStore/Okio
metadata/binaries, runtime proto/shaded notices and any actually resolved JSON
transitive/POM inputs. Normal integration also requires narrowly admitted
`android/gradle/dependency-locks/app.lockfile` and the actual core:data lock,
coordinated with WP-202. The actual narrowly admitted locks and owned
`gradle.lockfile` are generated by real successful Gradle resolution, not
handwritten from artifact summaries. WP-202 now owns its separate local
core:data lock, not this shared root lock. No other lock coordinate/content
delta was introduced. Gradle re-emitted existing unleased lock bookkeeping;
every such file was independently checked canonically identical to immutable
HEAD, with no unleased Git content diff.

The requested independently corroborated **actual binary and POM inputs** are
in [`publisher-inputs.json`](publisher-inputs.json):18 exact module/POM
publications plus the actually observed existing serialization1.7.3 BOM,
10 actually downloaded JAR/AAR byte counts/SHA256/SHA1 values,
Android/JVM variant attributes, declared dependencies/version constraints,
POM licenses and exact bundled copyright/license hashes. The initial18
compile-artifact failure is preserved separately rather than relabeled as a
complete runtime graph. Actual reports additionally request the already
source-declared serialization-JSON1.7.3 pair; no unpinned version was introduced.

The relocated protobuf publication is **BSD-3-Clause**, not Apache-only.
Its1434-byte embedded license is retained verbatim at the coordinator's exact
additional leased source path
`android/app/src/main/assets/licenses/DataStore-Protobuf-BSD-3-Clause.txt`.
The corresponding required APK path is
`assets/licenses/DataStore-Protobuf-BSD-3-Clause.txt`, bytes1434,
SHA256`a01b712fbb30f80b86851468b6fa58e80eab68f69f325e88bbc7ddf47cf1b063`.
That embedded text lacks a copyright header; it is **not** called a complete
copyright receipt on its own. Static ConstantValue fields in the actual
shaded RuntimeVersion class bind upstream protobuf4.28.2. The exact v28.2
tag/commit/license blob provides the1732-byte `Copyright 2008 Google Inc.`
license (SHA256`6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d`)
in owned module asset
`assets/licenses/WP-204/Protobuf-v28.2-Copyright-LICENSE.txt`.
Both exact texts must be packaged; neither is substituted by a generic Apache
notice. No publisher artifact code was executed.

Owned module assets also retain the actual AndroidX license, Okio Apache license and copyright
lines extracted from its checksum-pinned3.9.1 source archive. The Okio POM's
`square/okio` origin currently redirects to `lysine-dev/okio`; the exact
annotated3.9.1 tag object, immutable commit and license blob/hash are recorded,
with no signature or human license approval claimed.

The exact DataStore core Android AAR **contains native code**:
`libdatastore_shared_counter.so` for arm64-v8a,armeabi-v7a,x86,x86_64.
Independent static input inspection records all4 hashes/byte sizes and every
ELF PT_LOAD alignment as16384. It is not native-free, loaded on a device or
hardware-certified. Final actual APK inspection must locate all4 packaged
libraries, reparse their actual ELF headers and run real `zipalign -P16`;
AGP stripping may change their byte hashes and is recorded, not treated as
an inferred hardware/native-load pass.

[`lock-admission.json`](lock-admission.json) retains the actual initial app/data
lock blob IDs/hash/bytes and all12 potentially relevant existing configurations
per module. The precise proposal is only8 runtime/lint configurations per
module: `debugRuntimeClasspath`, `releaseRuntimeClasspath`,
`debugUnitTestRuntimeClasspath`, `debugAndroidTestRuntimeClasspath`,
`debugLintChecksClasspath`, `releaseLintChecksClasspath`,
`debugUnitTestLintChecksClasspath`, `debugAndroidTestLintChecksClasspath`.
An actual delta must control final lock generation; this proposal does not
manufacture already-resolved lock state or authorize a blanket graph rewrite.
It distinguishes those frozen records from the actually inspected
debug runtime and compile reports. Runtime/lint lock updates, not a blanket
root dependency graph rewrite, used the subsequent serialized coordinator
admission. The generated actual deltas and complete selected graph are
retained in `lock-admission.json` and
[`resolved-dependency-graphs.tsv`](resolved-dependency-graphs.tsv).
The raw [`runtime-dependency-report.txt`](runtime-dependency-report.txt) and
[`compile-dependency-report.txt`](compile-dependency-report.txt) are verbatim;
their successful report-command exit codes do not hide FAILED runtime nodes.

: convention
compiler class loading failed despite the exact private compiler JAR hash/class
being valid; a PowerShell stacktrace-rendering process overflowed and only that
command tree was stopped; a subsequent exact JDK preflight failed before Gradle
and fresh PowerShell could not load CoreCLR. Once exact JDK startup recovered,
the bounded read-only reports above completed. No other process, global config,
verification pin or dependency version was changed to hide those failures.

The real `:core:datastore:testDebugUnitTest` is wired through
`:core:datastore:verifyPreferenceTests` into the existing `verifyScaffoldTests`
task, following the actual merged l10n/model/database pattern. Once admitted,
run the smallest actual module test/collector, then normal strict native
protocol/model/datastore/root graph/runtime resolution, actual APK/inspection
and lint; retain normal same-head Windows/Linux schema2 raw JUnit/input/run/
attempt/artifact replay, not legacy dummy counts. No completion, PR readiness,
hardware, real Keystore TEE, API37 device, full backup oracle, legal/signing or
release claim is made before those executions and coordinator exact-head review.

See [native deviations](../../deviations/WP-204.md). The unchanged app already
depends on datastore and disables automatic backup/extraction, but source
inspection does not substitute for the required final actual APK proof.
The owned `:core:datastore:verifyPreferencePackaging` task now also hooks into
`verifyScaffoldTests` and depends on the real `:app:assembleDebug`. It inspects
the actual binary manifest's `allowBackup=false`, binds its extraction-rule
resource ID to the real compiled XML, requires all cloud/device-transfer
storage-domain exclusions, checks the five exact packaged notices, all4
DataStore native ELF libraries and static APK zipalignment, and scans
actual DEX for test helpers/known test-only secrets. This task is implemented,
**not executed/passed**. No app source amendment is currently justified by
the existing disabled-backup/all-domain rules plus credential-protected
`noBackupFilesDir`; if actual packaging reveals a gap, the only proposed
source amendment paths are `android/app/src/main/AndroidManifest.xml` and
`android/app/src/main/res/xml/scaffold_data_extraction_rules.xml`, still read-only.
The original module/docs lease, exact app BSD notice path and exact root
verification XML/app/core:data lock paths are active under the serialized
receipts. Other root locks/catalog/workflows/build files, backup config and
app resources remain read-only. No source backup-rule rewrite is justified. The exact notice verifier is
wired into actual module `preBuild` and app `mergeDebugAssets`, avoiding a
Windows-checkout race without fetching or regenerating licensed contents
during a build.

The additional copyright-asset proposal is exactly the owned module path
`android/core/datastore/src/main/assets/licenses/WP-204/Protobuf-v28.2-Copyright-LICENSE.txt`,
1732 bytes, SHA256
`6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d`,
from immutable upstream v28.2 license blob
`19b305b00060a774a9180fb916c14b49edb2008f`/commit
`9fff46d7327c699ef970769d5c9fd0e44df08fc7`. The actual shaded runtime class
fields are4/28/2, not an inferred generic version. This supplements, never
changes, the exact admitted1434-byte app BSD asset. Its provenance is
recorded for coordinator review; full legal/source obligations remain WP-503.

The exclusive bounded local slot was used for real113-case assertions, the
owned verifier and exact admitted lock resolution. APK D8 assembly exhausted
the512MiB budget in both the combined and fresh separated invocation, without
producing an APK. All own private Java processes are confirmed gone; the
worker gives back the local slot rather than increasing a budget, killing a
peer, disabling strict verification or claiming a packaged/hardware result.
The coordinator subsequently granted one more bounded owned-validation slot;
actual113-case and strict selected-graph proof passed on mergedfbb, with the
notice preBuild hook executing. Final actual APK/root/runtime/both-host proof
is now requested from normal isolated PR CI on that actual merged base. The
previous512MiB D8 failures remain retained, not relabeled as APK success.
The first normal2GiB hosted attempt produced an actual APK but correctly
failed the new verifier before any packaging pass was published. Diagnostics
from the independently verified PR20 APK exposed exact SDK output differences:
`http://schemas.android.com/apk/res/android:allowBackup(...)=false`,
`resource0x... xml/scaffold_data_extraction_rules`, and UTF8 resource text.
The owned parser now consumes those actual forms and requires one exact
false attribute, matching compiled resource ID and all9 domains in both
cloud/device transfer. Eight regression assertions run in the real packaging
task. Fresh same-head normal CI must still prove the repaired candidate; no
workflow, source manifest, policy, pin or threshold was changed.
No next work package, factory, agent, fleet, merge or approval was started.

## Independently reviewed defects reproduced and repaired

The read-only reviewer identified two real defects at the original
`ef903325391dfe8a0d0ec74d85b44c96df802199` production input. The earlier113
passes do not close them. The same branch/PR now implements explicit API35+
new-key unlocked enforcement (all explicit first/current-lock checks remain,
no existing key is changed) and a shared close boundary including registry
release for winning and losing/cancelled callers.

New tests use actual SDK31/32/33/37 Robolectric services/key specs, not merely
integer parameters, and a deterministic reversed-resumption dispatcher around
real DataStore shutdown with immediate get/reopen and replacement-owner
protection. They now execute: **131 discovered/131 passed/0
failures/errors/skips**,119 native methods,16 actual API profiles, and all
original34/41 source accounting unchanged. Complete10 raw JUnit suites and
profile labels are retained. These are actual SDK runtimes/service/spec
assertions, not hardware Keystore certification.

The first targeted attempt found two real test-setup failures: no process
observer remained active, so both closes could legitimately finish before
the deliberately reversed dispatcher scheduled a continuation. Using the
actual process-owned notification-preference observer makes both closes
suspend through real shutdown, without sleeps or fake cleanup.

Temporarily restoring **only the two reviewed original production behaviors**
then produced the expected18-case run with**4 failures/0 errors/skips**:
no-PIN generation failures on31/32/33 and immediate`get()` returning the
closed owner. Verbatim XML and failing identities are retained under
[`review-regression/original-defects/`](review-regression/original-defects/).
The API35+ policy/shared close implementation was restored byte-for-byte to
its committed source, and the full131-case suite passed unchanged assertions.
Reproduction failures are not passing acceptance evidence.

| Actual bounded command | Result |
| --- | --- |
| `:core:datastore:prepareReviewedPlatformSdks :core:datastore:resolvePreferenceDependencies --write-locks --dependency-verification strict --no-build-cache --quiet` | Passed actual module-local lock generation and independent JAR checks for31/32/33/37; selected graphs resolved, no new shared artifact/version |
| `:core:datastore:testDebugUnitTest --tests com.meshcoreone.android.core.datastore.KeystorePlatformPolicyTest --tests com.meshcoreone.android.core.datastore.OwnerCloseRegressionTest`, same strict flags | First16 policy cases passed and2 setup cases failed; real process observer repaired deterministic scheduling, then18/18 passed |
| Same18-case selector with temporarily restored original2 production behaviors | Failed as required:18 run/4 failures/0 errors/skips, both reviewed defects observed; original failing raw XML retained |
| `:core:datastore:verifyPreferenceTests --dependency-verification strict --no-build-cache --quiet` after restoring both fixes | Passed131/131,119 native methods, all34 originals/41 source parameter scenarios and16 API profiles |

The three additionally reviewed SDKs use **separate nontransitive module
configurations**`reviewedPlatformSdk32`,`reviewedPlatformSdk33` and
`reviewedPlatformSdk37`, preventing normal Gradle conflict selection from
dropping32/33 when all versions share one configuration. Actual strict lock
records were generated by Gradle, never hand-authored; existing31 and all
checksum/SDK/platform pins remain unchanged. The typed directory argument,
scoped Java export and one-class forks follow the published native multi-SDK
runner. No lock verification is disabled.

All commands used one private512MiB build/512MiB metaspace/256MiB test,
one-worker/in-process Kotlin/SerialGC/two-CPU configuration. The worker
confirmed**zero own private Java processes** and releases the exclusive slot
back for WP-202's guard verification. No local APK/D8 retry occurred.
Fresh normal same-head Windows/Linux root/APK/protocol proof and narrow
independent source recheck remain required before final handoff/merge.
