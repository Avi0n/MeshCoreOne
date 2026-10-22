# WP-501 inline image prefetch originals: observable orchestration predicates

Repository `cbattlegear/MeshCoreOne-Android`, issue134 / existing ready PR165.
LOCAL direct parent `a093e41cfc6f5568c8e5a61fd11efd48ba866311`; publicb2
and a093's complete resolver comparison are unchanged.
Base/main `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen source `db14559b39d32322b06477c6ae676112f583db50`;
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

## Entire selected family and retained assertions

Selected from the actual detailed WP-004 original inventory, not a filename
coverage count. Read all695 lines of the frozen test file: seven complete
originals, temporary-path helper, both actor stubs and entire persistence stub.
Read all native incumbent bodies/helpers, the complete source/native
orchestrator/classifier and dimensions store, exact preview role/results, URL
extraction and relevant actual mounted admission/content/preview-cache code.

| Complete original display identity | Preserved exact input and expectations |
| --- | --- |
| `Text with no URLs returns immediately without probes or previews()` | `"hello world, no links here"`, false/true; both call lists empty |
| `Direct image suffix invokes the dimension probe path()` | `"look at https://example.com/cat.png"`, false/true; exact sole cat.png probe, no previews |
| `Image probes are skipped when disallowed but card URLs still resolve()` | `"image https://example.com/cat.png and article https://example.com/article"`, false/false; no probes, exact sole article preview |
| `Multiple URLs fan out across both classifier paths()` | Same mixed text, false/true; exact cat.png probe and article preview |
| `Direct image probe is skipped when dimensions are already cached()` | Cat.png seeded200x100; exact original look-at text, false/true; no probe |
| `Mixed direct image and link preview URLs both invoke their paths()` | `"see https://example.com/cat.jpg then read https://news.example.com/post"`, true/true; one exact probe, one exact preview and channel flags `[true]` |
| `Giphy hosting URL routes to probe path with resolved direct image URL()` | `"look at https://giphy.com/gifs/abc123"`, false/true; sole resolved probe, no preview |

Boolean pairs mean isChannelMessage/allowImageProbes. No original injects
errors/cancellation; all prober stubs return nil and preview stubs return
NoPreviewAvailable. Native `InlineImagePrefetcherTest` retains every complete
equivalent body plus its disclosed eighth Loaded/hero-aspect addition.
No incumbent function, full input, result, helper or runtime limit is changed.

Two weaknesses are observable without inventing a product defect:
immediate-return fakes let a serial implementation satisfy both final call
lists, and the native Giphy expectation invokes the same candidate classifier
used by the subject. The source's classifier literally constructs
`https://i.giphy.com/media/\(giphyID)/giphy.gif`; the new expectation for frozen
inputabc123 is the independent literal
`https://i.giphy.com/media/abc123/giphy.gif`, never computed by Kotlin.

## Three supplementary tests against actual production orchestration

New `InlineImagePrefetcherOriginalPredicateTest` calls the real
InlineImagePrefetcher, classifier/extractor and persistent dimensions store.
It does not copy their algorithm or claim new original-family credits.

1. `originalMultipleUrlsReallyFanOutAndJoinBothProductionBranches` retains the exact original mixed text/false/true and NoPreviewAvailable result. Both role calls must start before either release; the request stays active while either peer is held. BOTH release orders prove joining each branch, not only the last URL. Exact URLs, false channel flag, original persistence-role identity, single call per branch, complete normal exits and absent aspect writes are asserted.
2. `cancelledFanoutJoinsOwnedChildrenWithoutLateDimensionsAndKeepsLiveWritesObservable` uses the same mixed input but deliberately supplies a held Loaded200x100 article DTO with fixed fetchedAt to make a late real write observable. Cancelling/joining the request must propagate CancellationException, exit both owned branches without returning, leave both gates unreleased and create neither aspect nor file. Releasing gates afterward cannot revive work/write. A live call on the SAME subject/store then disables direct probes and actually persists/reloads article aspect2.0; this positive control prevents an inert dimensions store from masking cancellation errors.
3. `originalGiphyProbeUsesIndependentFrozenMediaUrlAndOnlyTheResolvedCacheEntry` retains the exact original hosting URL/look-at text/false/true. Seeding only the hosting key200x100 must NOT skip the exact literal media probe. Seeding the resolved key200x100 then must skip another probe; neither call may use the card path. Both real file-backed cache keys reload as2.0. These additional cache states are disclosed combinations, not original parameters/credits.

Production coroutines execute on the injected test scheduler. No sleeps,
extra timeout, arbitrary retry or timing/deadline changes. Both held-request
tests cancelAndJoin in finally; branch exits/continuations are asserted.
JUnit TemporaryFolder owns the disposable files; store/DTO clocks are fixed.
The narrow persistence-role stub rejects unexpected operations explicitly:
it proves forwarding identity, NOT Room/database behavior. Image/cache roles
are controlled seams as in the originals; no network, decoder or radio is used.

The cancellation witness concerns the orchestrator's owned child waits only.
The real LinkPreviewCache has independent worker-owned shared fetches which may
continue warming caches after a caller cancels; this test does NOT replace that
producer or assert that all process-owned network work must stop.

## Actual consumer limit and bounded producer prerequisite

The frozen `ChatViewModel+Prefetch.admitIncomingMessage` bypasses prefetch when
missing/disabled/URL-free, otherwise races it against the three-second caller
timeout with the conversation privacy flag, cancels the group and then admits
the message. Outgoing messages render immediately and prefetch asynchronously;
dimension-resolution events rebake matching rows. Those consumer semantics
are not tested by directly calling this service.

The actual native mounted App factory uses feature ChatViewModel; its
MessageReceived handler calls admit directly. The complete admit body appends
the message/builds rows, without a receive-time prefetch race. Production
AppChatContent composes preview/image caches and demand-load policy, but never
constructs this prefetcher. The optional App-state primer interface is not a
mounted receive pipeline or evidence that these seven cases execute there.
The production constructor/use search agrees with the actual consumer bodies.

Route this bounded consumer prerequisite to the existing timeline/App producer:
wire actual receive-time prefetch/admission and outgoing/resolution lifetimes,
preserving the original master/privacy gates, caller timeout and independent
cache-worker cancellation semantics, with a real mounted witness. No WP-501
product patch, new producer activation or speculative replacement is made.
Mounted prefetch/admission equivalence remains BLOCKED; these tests only
strengthen real service predicates. The a093 flood-scope prerequisite stays
separate and unchanged.

## Exact inputs, admission and verification boundary

All six complete frozen files below equal current tracked bytes after host
CRLF normalization. No selected Swift drift, source amendment or pin advance.
Git paths are provenance identifiers.

| Frozen input | Git blob at db145 |
| --- | --- |
| `MC1Tests/Services/InlineImagePrefetcherTests.swift` | `8d2f1f5858a08801a666f7425a48b3a20cc50c06` |
| `MC1/Services/InlineImagePrefetcher.swift` | `0192ac4e6ba7e14c194cba7bc1184b4a8fe632e0` |
| `MC1Services/Sources/MC1Services/Utilities/ImageURLClassifier.swift` | `359385a0ecee79d2cf6e630aa3bff8d70721c446` |
| `MC1/Views/Chats/ViewModel/ChatViewModel+Prefetch.swift` | `c3eb75cb44c609336b76bdf8f0a6b253e3438305` |
| `MC1Services/Sources/MC1Services/Services/InlineImageDimensionsStore.swift` | `9cf140fcb4008f15ec490f31f883bee8ebec5ac5` |
| `MC1/Services/LinkPreviewCaching.swift` | `6a5c170c038b30981e94f91fb89805016f234f3f` |

| Native input at parenta093 (under `android/`) | Git blob |
| --- | --- |
| `core/services/src/test/kotlin/com/meshcoreone/android/core/services/content/InlineImagePrefetcherTest.kt` | `a5ad0a674ba3b111b7c7f00cd1168ed81a682f57` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/InlineImagePrefetcher.kt` | `8dfbf489ddb0cb02279b48572a865c99f1206d72` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/ImageUrlClassifier.kt` | `dbc2810bd9d0d611266c27768419dcecf4d9cc44` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/InlineImageDimensionsStore.kt` | `2e62fdb800de425a89b8350349cbc12ddad6c35f` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/LinkUrlExtraction.kt` | `0df0e38c489b009b9253329a7d9f417a6e53f57c` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/LinkPreviewCaching.kt` | `f46454b6fba95d1f34c90c2f2427a4bf8c6ecf60` |
| `core/services/src/main/kotlin/com/meshcoreone/android/core/services/content/LinkPreviewCache.kt` | `b19c57963d9f92e7f666aeec43d0bebde0e4ee78` |
| `core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/DiagnosticPersisting.kt` | `a16f7636904cfd224193013f3050163bf9d573ec` |
| `core/model/src/main/kotlin/com/meshcoreone/android/core/model/DiagnosticDTOs.kt` | `439a39d0a49c3be28e26e4b829a419e82502d332` |
| `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt` | `643c3e9b484b523dc7b533937ce91eb88f425bc8` |
| `app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatContent.kt` | `75e2a75f0e6ffd4e7c889fe1b018dec8c7efb9e4` |
| `app/build.gradle.kts` | `2d6d28fbecbcca2bd479535b36508a2f95565182` |

Before editing, same actual owner/attempt/session
`d6056690-3a5d-453e-b67e-36866c676ac5` evolved102->103 through nine wp-owned
create/modify/record-evidence admissions for only new test/evidence and the
existing deviation. Immutable authorization, identity/null fields, historical
binding and existing capabilities remain unchanged. The existing App test
source-set already includes parity-tests; no build/dependency change.
Removing only this increment's deviation section restores the ENTIRE parent;
all incumbent/source/support/a093/history bytes are retained.

Declared source/whitespace command outcomes are reported at the exact committed
head in the handoff. Preparation portmap rejected conflicting PortedFrom and
AndroidOnly headers; the new file now retains only its pinned PortedFrom
disposition, without a validator waiver. Three new methods are UNCOMPILED/UNRUN/UNDISCOVERED:
no local Gradle/native/WSL/runtime/readiness/device/lease/deployment/protected
action, CI dispatch/rerun or publication. Parent execution does not transfer.
Hold LOCAL for combined source review. No broad inventory regeneration,
CI-result bundle or protected/full-WP/native/backup acceptance claim.
