# WP-102 wire-crypto evidence

**Local candidate verification passed: 885 actual protocol cases, including
363 crypto cases, plus dependent suites, graph, inventory, lint and the real
APK/notices. Exact-head normal hosted results remain the final publication
check. Previous real failures are retained below.**

## Issue #371 follow-up

On 2026-10-27 the user approved strict Android Ed25519 point validation as an
intentional adaptation: wrong lengths raise `InvalidKeyLength`; malformed
32-byte points raise `InvalidEd25519PublicKey`. The direct conversion test now
asserts representative malformed-point failures within the original invalid-
input test method, preserving the pinned test identity. Existing independent
key, round-trip, ECDH, and converted-key DM vectors cover the valid path.

This follow-up is **not locally verified**. The manifest still has no
WP-102-specific verification commands; the earlier 885-case evidence below
belongs to its recorded candidate and does not verify these edits. The
authoritative exact-head hosted result is recorded in the PR description; no
second CI-result bundle is stored here.

Repository: `cbattlegear/MeshCoreOne-Android`. Owner: `protocol-porter`.
Session: `dbffc569-419e-4dcc-ace0-836b58f6435c`.
Managed branch: `cbattlegear-legendary-adventure`.
Initial merged base: `4331a4dddd13126ab05f4b4d74e654f313c5d414`.
Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest canonical SHA256:
`78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746`.
Semantic `controller.gates.policy_revision` at that base, independently recomputed:
`56bdc53548bc86d631245795dfa38b4fc86048e0e7cbe1c7d5695879b035b42a`.
Optional canonical policy JSON SHA256:
`3a96956793753399f3fe9bfb06927df3ad70998478731f3f29889a57407bac20`.
These are different by definition: semantic revision includes manifest/source
and excludes operational switches, whereas the JSON digest hashes that file's
whole canonical object. The initial comparison incorrectly called this drift;
the coordinator clarified the distinction and the exact semantic function
confirmed the original pin. No source/policy pin/file, automation setting,
fleet, protected gate or self-approval changed.

The kickoff/renewal authorizes the four exact manifest crypto/evidence scopes.
The coordinator subsequently authorized exactly the protocol implementation
dependency, BC catalog alias/version, actual-required BC-only lock changes,
BC1.86 JAR/POM verification entries after releasing WP-005's XML lease, and
`android/app/src/main/assets/licenses/BouncyCastle-MIT.txt`. The amendment is
scoped and serialized, not a blanket shared-path exception. The coordinator
records the lease identity; no formal receipt is manufactured. The actual PR
head/check suite binds candidate results, not a self-referential evidence SHA.

## Behavioral implementation

The seven production files provide source channel/direct decrypt outcomes,
strict UTF8/NUL semantics, full-secret HMAC-SHA256 and two-byte constant-time tag
comparison, source AES128 ECB/no-padding/zero-extension, X25519 agreement,
validated Ed-public-to-X conversion, SHA512 seed expansion, scalar extraction,
and real seed/**expanded** Ed25519 signing/verification. Existing immutable
`Bytes` and `model.sha256` are reused.

Read consumers include WP-212 `RxLogService` (64-byte firmware key truncation,
converted Ed contact keys, nullable DM text with useful timestamp), WP-204
`KeyGenerationService` (SHA512 expansion/clamping and 64-byte validation), and
WP-107 `MeshCoreSession+Signing` (radio-driven signing, not a local fake).
The [deviations](../../deviations/WP-102.md) specify typed invalid-key adaptations,
known failure ordering, native types and genuine platform limits.

| Acceptance | Evidence and current status |
| --- | --- |
| `WP-102-behavior` | **Passed locally**: original scenarios and independent byte assertions execute in the actual 363-case crypto suite |
| `WP-102-boundaries` | **Passed locally**: malformed/tag/key/curve/UTF8/padding/format/provider-list cases, strict graph, dependent Android packaging and exact notices |
| `WP-102-source-test-parity` | **Passed locally**: all22 original declarations and the0/1/2 loop execute; pinned-input/reflection assertions bind the concrete native methods and source oracle |

## Complete original case mapping

All source paths below are under `MeshCore/Tests/MeshCoreTests/` at the exact
reference. Production inputs are the three corresponding protocol files in
the frozen manifest; all six owning blobs, MIT license and Python fixture
cross-reference are checked by `PinnedCryptoInputsTest`.

| Original suite/case | Concrete Kotlin assertion / independent fixture |
| --- | --- |
| Channel `Decrypt success` | `ChannelCryptoTest`; `channel-normal`, also the actual compiled Swift packet |
| Channel `Decrypt wrong key` | Exact `.HmacFailed` against `channel-wrong-key` with original wrong secret |
| Channel `Decrypt corrupted MAC` | Both source MAC bytes XORed with FF; exact `.HmacFailed` |
| Channel `Decrypt payload too short` | Original `00010203`; exact `.PayloadTooShort` |
| Channel `Decrypt empty message` | `channel-empty`; timestamp0/type0/empty text |
| Channel `Decrypt long message` | `channel-long`; same original full text, multi-block independent ciphertext |
| Channel `Decrypt unicode message` | `channel-unicode`; same original CJK/emoji UTF8 |
| Channel `constants` | All five original constants |
| Channel `Decrypt with different txtTypes` | Same source loop **0,1,2**, independently specified `channel-type-*` rows |
| Channel `Decrypt with 32-byte secret uses first 16 bytes for AES` | Same original 32-byte secret and text; independent full-key HMAC and first16 AES; truncating the HMAC key fails |
| Direct `Decrypt success` | `DirectMessageCryptoTest`; `direct-normal`, with fixed independently proven RFC7748 keys replacing source random keys |
| Direct `Decrypt wrong key` | Deterministic distinct private key; exact `.MacMismatch` |
| Direct `Decrypt corrupted MAC` | Same source offsets2/3 and FF mutations; exact `.MacMismatch` |
| Direct `Decrypt payload too short` | Original `00010203`; exact `.InvalidPayload` |
| Direct `Decrypt empty message` | `direct-empty`; same timestamp0/type0/empty text |
| Direct `Decrypt unicode message` | `direct-unicode`; same original UTF8 |
| Direct `Extract timestamp` | `direct-extract`; original timestamp via convenience consumer API |
| Direct `constants` | All six original constants |
| Direct `Invalid key length` | Same 24-byte packet, two-byte private input and zero32 public input; exact `.KeyError` |
| Ed `Public key conversion round-trip with CryptoKit` | `Ed25519ToX25519Test` uses independently specified RFC keys/scalars, both ECDH directions and expected conversions; no claim Kotlin executes CryptoKit |
| Ed `DM decrypt with converted Ed25519 keys` | `ed-converted-direct`; same original Hello/timestamp semantics and independently produced full packet |
| Ed `Conversion rejects invalid inputs` | Original 0/16-byte families map to explicit typed length failure, not silent nil |

There are **10 + 9 + 3 = 22 original declarations**, not 22 generated files or a
claim that each loop row is a separate declaration. Additional native factories
exercise every independent fixture, every MAC bit, truncation/alignment,
secret/key/signature lengths, noncanonical/low-order peers, expanded clamp bits,
nullable invalid direct text, NULs, high-bit timestamps/type bytes, source error
precedence and untouched global providers. Real JUnit testcase counts are still
required; filenames/reflection alone are not acceptance.

## Independent bytes and provenance

`generate_vectors.py` imports only existing Python `cryptography`48.0.0 plus
standard-library SHA512/HMAC. It never invokes/imports Kotlin or asks candidate
code for expectations. It uses three official RFC8032 section7.1 vectors,
RFC7748 section6.1 keys/shared secret, and source test parameters. The library's
public keys/signatures/shared secret must independently match the literal
standards before fixture generation succeeds.

Generated, fully consumed fixtures:

| File | Rows | Contents |
| --- | ---: | --- |
| `message-vectors.tsv` | 74 | All source messages plus zero-length/boundary/UTF8/NUL/non-block/short and variable-secret cases |
| `key-vectors.tsv` | 19 | RFC plus sixteen deterministic independent seeds; full SHA512-expanded64, Ed/X public keys and X private32 |
| `signature-vectors.tsv` | 13 | Three official standards and independent byte inputs of lengths16/31/32/33/120/121/255/256/1024/65536 |

`vector-generation.json` records the source blobs, independent tool/version,
counts and complete canonical-LF fixture SHA256 values. Only CRLF/LF Git text
normalization differs across hosts; hex wire bytes are never normalized.
The Kotlin test loader fails on missing/extra/duplicate/malformed/zero-count
cases. No `@Ignored`, `@Disabled` or candidate-generated expected packet exists.
JDK21 Ed25519 is an **independent test verifier only**, not production Android
provider proof.

Both existing actual Swift oracle packets match the independent Python bytes:
normal and high-bit UTF8, from
`android/core/testing/fixtures/reference-codec/channel-crypto-oracle.json`
with SHA256
`f6a323cf0e351d2dc7eb5bda26cb808ede7fb03dcb2bbe132e7735726deaa1f7`.
WP-004 executed that source oracle; **Swift was not executed here**. The
cross-reference `PythonReferenceBytes.swift` contains command/LPP/control
fixtures, not invented crypto ciphertext; its bytes and canonical owner are
unchanged. Production has no `core:testing` dependency.

An initial RFC8032 test3 literal transcription was rejected by the independent
library before producing fixtures. It was corrected against the actual RFC
signature, not against candidate Kotlin. All three standard literals then
passed, as did both prior Swift oracle comparisons.

A private Java8-targeted dependency API probe also rejected BC's unpruned
`expandPrivateKey` bytes against the independent firmware-expanded64 expectation.
The exact publisher implementation only hashes the seed; pruning is explicit or
performed on-use by other BC methods. Production now calls the vetted
`ExpandedKey.prune` **after generating** an expanded key, never on invalid
imported keys. This is a real format correction, not a changed golden or a claim
that the private probe ran candidate Kotlin/Gradle or Android devices.
After the correction, the exact verified JAR probe passed all19 independent
identity/conversion rows, all13 signatures, seven invalid Ed points and six
noncontributory X peers with an unchanged global provider list. A dedicated
native regression also asserts pruning plus preservation of the independent
SHA512 nonce half and now passes in the actual candidate Gradle suite.

Dependency-research commands were `javac -J-Xmx128m --release 8 -Xlint:-options
-classpath <verified-bc-jar> -d <private-probe-directory> <private-BcApiProbe.java>`
and `java -Xmx96m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -classpath
<private-probe-directory>;<verified-bc-jar> BcApiProbe <WP-102-vector-directory>`.
The first unpruned run **failed**; the corrected run **passed** the counts above.
These are private dependency capability probes, not new Gradle tasks, candidate
JUnit discovery, independent parity review or Android runtime acceptance.

## Actual commands and outcomes so far

All Gradle calls use the existing allowlist launcher, exact existing verified
JDK21.0.12.1/SDK37.2/build-tools37.0.0, bytecode17 and separate private WP-102
user/Android caches. Environment paths are session-local, not committed.
Memory settings are build-process options, not application runtime behavior.

| Actual command | Result |
| --- | --- |
| `python android\scaffold\check_environment.py` | **Passed**, exact tools and isolated environment; no installation/license/device approval |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @(':core:protocol:test','--dependency-verification','strict','--quiet')` | **Passed on clean initial base only**: 522 testcase nodes, 0 failures/errors/skips; not candidate crypto acceptance |
| `python tools\android-port\controller\validate.py` | **Passed**: pinned1,866 reference files, 65 WPs, 185 edges and expected manifest digest |
| `python tools\android-port\controller\verification_config.py --check` | **Passed**; owning feature verification remains unconfigured in the canonical manifest, not silently amended |
| `python tools\android-port\portmap.py` | **Passed** for current source/test traceability, not a behavioral verdict |
| `python tools\android-port\test_inventory.py --check` | **Passed**: all468 input paths, 5,133 original declarations, 70 parameterized declarations and 382 declared parameter rows; **0 claimed ported cases** in the canonical frozen catalog |
| `python tools\android-port\extract_vectors.py --check` | **Passed**: all49 preexisting pinned independent fixture entries; no candidate Kotlin executed and no shared expectations changed |
| `python docs\android\evidence\WP-102\generate_vectors.py --regenerate` | **Passed** after correcting the rejected RFC transcription: 74/19/13 complete independent rows |
| `python docs\android\evidence\WP-102\generate_vectors.py --check` | **Passed** against those independently produced bytes and prior Swift oracle |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @(':core:protocol:test','--tests','com.meshcoreone.android.core.protocol.crypto.*','--dependency-verification','strict','--quiet')` | **Historical failure at compileKotlin** before admission: unresolved BC APIs; no candidate JUnit produced |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @(':core:protocol:test','--tests','com.meshcoreone.android.core.protocol.crypto.*','--write-locks','--dependency-verification','strict','--quiet')` | **First admitted run failed**: 363 actual cases, one rejected NIST literal; no skips. Corrected independently as described below |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @(':core:protocol:test','--write-locks','--dependency-verification','strict','--quiet')` | **Passed**: 885 actual cases, including363 crypto and522 existing; 0 failures/errors/skips |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @('validateModuleGraph','resolveScaffoldDependencies','runtimeDependencyInventory','--write-locks','--dependency-verification','strict','--quiet')` | **Passed** actual graph/resolution/runtime inputs; 27 normalized lock deltas contain only BC provider versions/configurations |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @('verifyScaffoldTests','verifyRoomSchema',':core:testing:testDebugUnitTest','validateModuleGraph','resolveScaffoldDependencies','runtimeDependencyInventory','--dependency-verification','strict','--quiet')` | **Passed without generation flags**: convention31/contracts4/app10/Room2/helpers35, all 0 failures/errors/skips; real schema/graph/inventory |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @(':app:assembleDebug','--dependency-verification','strict','--quiet')` | **First attempt failed** in BC dex transform; same-budget strict dex-only retry and subsequent full assembly **passed** |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @(':app:mergeExtDexDebug','--dependency-verification','strict','--stacktrace','--info')` | **Passed** the real BC transform/merge at the unchanged640MiB budget; no transformed/unverified vendor JAR or excluded classes |
| `invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 640m -GradleArguments @('lintScaffold','--dependency-verification','strict','--quiet')` | **Passed** declared dependent Android lint |
| `python android\scaffold\inspect_apk.py` | **Passed** actual debug package/min31/target37/launcher/permissions/notices and absence of test fixtures |
| `zipalign.exe -c -P 16 4 android\app\build\outputs\apk\debug\app-debug.apk` from exact SDK37 build-tools | **Passed** static ZIP alignment; not device/ELF runtime certification |
| `python docs\android\evidence\WP-102\capture_local_evidence.py` | Captures actual successful reports using existing strict controller parsers; complete JSON artifact and outcomes below |

The first admitted run caught a 129-digit manually transcribed NIST ECB
ciphertext. Its odd trailing digit was intentionally accepted by the existing
source-faithful hex helper, but that is inappropriate for a golden expectation.
The four expected blocks are now each asserted to have32 digits and match the
independent standard/OpenSSL/Python reference, not candidate output. No threshold
or source expectation was lowered. The attempted official NIST PDF endpoints
returned403; their execution is not claimed.

Current raw XML under `android/core/protocol/build/test-results/test/`
contains the **actual885-case successful candidate run**, not the old522
baseline. Compilation failures did not generate new successful XML and are not
used as acceptance.
The normal hosted protocol workflow runs all cases on Windows/Linux and retains
raw JUnit; it needs no workflow edit for this WP.

| Crypto suite | Actual cases | Failed/errors/skipped |
| --- | ---: | --- |
| Channel original cases | 10 | 0/0/0 |
| Direct original cases | 9 | 0/0/0 |
| Ed-to-X original cases | 3 | 0/0/0 |
| Independent message/framing | 142 | 0/0/0 |
| Curve/signature/conversion | 40 | 0/0/0 |
| Malformed/error/order/key boundaries | 124 | 0/0/0 |
| Standard SHA/HMAC/AES/tag bits/providers | 30 | 0/0/0 |
| Pinned source/case/oracle/license evidence | 5 | 0/0/0 |
| **Total crypto** | **363** | **0/0/0** |

`local-verification.json` records actual complete protocol/dependent/helper
JUnit discovery, raw report hashes, all24 lint targets, graph/runtime/discovery
artifact hashes, tested source/build/lock input hashes and the actual APK/notices.
Its generator reuses `controller.ci_evidence` so missing/malformed/zero/skipped
reports fail. Local working-tree evidence is not a privileged exact-head gate;
the published candidate's normal hosted checks supply that binding.

## Exact admitted dependency / amendment

Required inspected artifact: `org.bouncycastle:bcprov-jdk18on:1.86`.
Origin: Maven Central publisher directory
`https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/`.
The downloaded JAR/POM independently match the publisher `.sha256` responses:

| Artifact | SHA256 |
| --- | --- |
| JAR | `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f` |
| POM | `970b70cb75a184639b6fb9ba3c465aca6a4e1a5f3fff4d65ce8574b2fd79ef38` |
| Publisher sources JAR checksum, not downloaded/executed | `34a8b7dcb030a08ddd5356d3d5be962f9299ca5275e1460273186f8fdbe44c34` |

The exact artifact has no POM dependencies; base curve classes are major52
(Java8). Actual `javap` inspection verifies `X25519.calculateAgreement`,
`Ed25519.validatePublicKeyFull`, field operations, and
`Ed25519.ExpandedKey.expandPrivateKey/generatePublicKey/sign`. The last API
supports actual firmware-expanded signing without treating expanded bytes as a
seed or implementing curve/signature arithmetic locally.

`META-INF/LICENSE.md` contains the MIT copyright/permission/disclaimer,
Copyright(c)2000-2026 The Legion of the Bouncy Castle Inc. Preserve it for
distribution. Artifact facts are not fabricated legal/release approval.
Existing GPL application/MIT MeshCore notices are untouched.

The coordinator explicitly approved and serialized the bounded amendment:
`android/core/protocol/build.gradle.kts` (private implementation dependency),
`android/gradle/libs.versions.toml` (exact artifact/version),
`android/gradle/verification-metadata.xml` (only verified POM/JAR entries, after
WP-005 released the XML lease), and actual affected existing runtime lockfiles
in `android/gradle/dependency-locks/`. Only two verified BC artifact entries and
27 actual-required BC-only locks changed. All old XML coordinates/checksums and
trust settings remain byte-for-byte equal by normalized element comparison.
There is no broad reset, verification weakening, unrelated repin, provider
replacement or shared build/workflow-tool change.

The current APK assets contain only the incumbent GPL/MeshCore MIT/Apache
notices before this amendment. The coordinator explicitly authorized
`android/app/src/main/assets/licenses/BouncyCastle-MIT.txt`; it is extracted
from the verified JAR's exact `META-INF/LICENSE.md`, preserves the existing
three notices, and **matches all1171 bytes in the actual APK**. The publisher notice
SHA256 is `0e01f1549c9022f406392ac2947d32223b7c2e977d21ea2f8c182fdeb4dae5fd`.
This is the existing notice-directory pattern, not a new SDK exception,
fabricated legal gate or permission to change other resources.

The inspected debug APK SHA256 is
`8ef1d0595c1c0fb386b54d77d15b15a71fb9aeddb9663b3b0f75b6a9990e7ffe`;
it is35,000,695 bytes and retains all four notices byte-for-byte. The structured
APK inspector proves debug identity/min31/target37 and no testing-module leak.
Hosted checks bind the final published head; a subsequent base reconciliation
must rerun the affected actual commands. Physical API31/API37, HIL, legal release
and signing gates have not been executed and are not claimed from JVM/package
inspection or ordinary coordinator dependency admission.
