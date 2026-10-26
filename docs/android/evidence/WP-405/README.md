# WP-405 evidence

## WP405-LINKS repair verification status

The APP-G03/APP-G04/APP-G07 repair batch is **not Android-verified yet**.
The passing results below belong to the original WP-405 implementation at its
recorded historical base, not this repair candidate. They must not be reused as
new test-discovery, compilation, build or consumer-parity evidence.

The repair preserves private-secret hashtag warning/scope metadata through the
mounted confirmation and reuses the unchanged protocol grapheme helper with the
shared 30-byte region limit in external, chat and QR parsers. Original source
remains pinned to `db14559b39d32322b06477c6ae676112f583db50`.
The feature-parser capability was freshly admitted after trusted support landing
`f2f481d2e7c2a9029808943529c98c98ef879299`; the same owner's reservation
evolved to revision 4 before that edit.

The committed regression definitions require these real consumer surfaces:

| Test class | Defined repair methods | Required behavior |
| --- | --- | --- |
| `app.container.LinkConsumerIntegrationTest` | 4 | Actual retained Intent delivery/binding, original San Francisco focus, once-only consumption and external/chat confirmation metadata |
| `app.container.ChannelConfirmationConsumerTest` | 5 | Mounted warning/scope before consent, exact confirmed firmware/Room state, disconnected disabled action and cancellation |
| `app.container.ChannelRegionConsumerTest` | 4 | External/chat/QR parser-to-real-join and persisted region/secret, absent/empty values, padded ASCII and whole combining-grapheme boundaries |
| `app.container.MentionNavigationConsumerTest` | 2 | Actual mounted mention producer/action to contact destination and non-mention dispatch, preserving all original encoded-name vectors |
| `app.platform.ChatMapPreviewTest` | 2 coordinate methods | Independent mounted San Francisco/London thumbnail clicks replace prior focus with exact coordinates |

These are method definitions, **not discovered or passing counts**. API expansion
and the complete existing module suites must be observed from fresh real reports.
The QR assertion covers the production parser/join seam, not camera or device
evidence.

After reconciliation with trusted main
`a8c624bcb095f81a30e5fee671099fa519f608b7`, read-only affected-plan derivation
selects `module:app` and `module:feature/chats`. The supported launcher contract is:

```text
python tools/android-port/local/check.py --commit <exact-candidate-SHA> --affected-base <current-trusted-main-SHA>
```

The derived obligations are complete `:app:testDebugUnitTest` and
`:feature:chats:testDebugUnitTest` runners, App/chats Kotlin compilation and lint,
`:feature:chats:check`, `:app:compileDebugAndroidTestKotlin`,
`validateModuleGraph` and `runtimeDependencyInventory`. No arbitrary Gradle
filters, direct execution workaround or full-scaffold widening is requested.
Remote source preparation is not heavy-execution admission. The coordinator
must assign the supported remote execution slot before that run starts.
Publication remains pending combined meaningful nonzero discovery and PASS.

## Historical original implementation evidence

## Scope and provenance

- Work package: `WP-405`
- Owner: `platform-integrations-engineer`
- Integrated base: `e4a4eb1e41ed4616c66271c519ce1637c6375c83`
- Frozen Swift source: `db14559b39d32322b06477c6ae676112f583db50`
- Frozen Swift tree: `8918fdc604341e6996a68c88f6bb1c02b9c2f87e`
- Manifest digest: `58f7ebd7f46bbe0636c71005f20776efe139a287279e4f4708b25ce6bfa3f892`
- All five owned production sources and four owned Swift test files were compared to the frozen revision without drift.

The 47 owned frozen Swift test cases are named by `@SourceCases` annotations in
the WP-405 JVM tests. They are exercised by 26 discovered tests covering
contact/channel/map parsing, malformed and encoded input, query injection,
identity handling, hashtag/mention support, flood-scope application, typed
confirmation staging, pending cold-start delivery, duplicate suppression, and
cancellation retry.

## Security boundary

The exported manifest entry points are limited to:

- `meshcore://map`
- `meshcore://contact/add`
- `meshcore://channel/add`
- `meshcoreone://hashtag/<name>`

Routing recognizes only those typed forms. It can navigate to an existing
object or stage a typed confirmation request. There is no generic command,
shell, radio administration, or mutation dispatch surface reachable from an
external URI. Internal mention URIs are not exported.

## Verification

Run after integrating `origin/main` at the base above, with the repository
credential-stripping launcher, Temurin 21.0.12.1, compile SDK 37.2,
build-tools 37.0.0, strict dependency verification, and explicit isolated
Gradle/Android caches:

```text
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 1024m \
  -GradleArguments @(
    ':app:testDebugUnitTest',
    '--tests', 'com.meshcoreone.android.app.deeplinks.*',
    ':app:lintDebug',
    ':app:assembleDebug',
    'validateModuleGraph',
    '--dependency-verification', 'strict'
  )
```

Result: passed. Deep-link JVM discovery was 26 tests in 4 suites; 26 passed,
0 failed, 0 errors, 0 skipped. App lint produced 0 errors and 28 existing
warnings. `:app:assembleDebug` and `validateModuleGraph` passed.

```text
.\android\gradlew.bat :app:testDebugUnitTest \
  --tests com.meshcoreone.android.app.navigation.NavigationActivityTest \
  --dependency-verification strict --no-daemon
```

Result: passed; 4 activity/navigation lifecycle tests passed, 0 failed,
0 errors, 0 skipped. This verifies the WP-405 `MainActivity` integration
against the current WP-303 onboarding binding.

```text
python .\tools\android-port\portmap.py
```

Result: passed with exit code 0.

```text
python .\tools\android-port\controller\validate.py
```

Result: valid with nonzero inventory (1,866 tracked reference files, 65 work
packages, 185 dependency edges).

An exploratory unfiltered `:app:testDebugUnitTest` run at a constrained 768 MiB
heap discovered 387 tests before the Robolectric worker exhausted metaspace;
this was an environment-capacity failure, not a WP-405 assertion failure. The
declared targeted WP-405 run and required app lint/build/module-graph checks
above all passed at the final integrated base.

## Ownership adaptations

The manifest-declared deep-link source/test and WP-405 documentation paths are
used directly. `AndroidManifest.xml`, `MainActivity.kt`, and
`NavigationState.kt` are directly necessary app-launch/navigation support
edits admitted by the trusted app-build-launcher capability. The manifest edit
is a protected-path change and remains subject to the repository's normal
human review/merge protection.
