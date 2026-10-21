# WP-502: native paint observation boundary

**LOCAL / UNPUBLISHED / UNVERIFIED, UNCOMPILED, UNRUN.** This corrective
successor retains the diagnostic finder repair and compares mounted native
raster observations with BOTH raw and allocation-clipped native paint, not
just an assumed allocation clip. No geometry repair, pixel result, discovery,
passing method, font/device acceptance or whole-WP completion is claimed.
The strict oracle is unchanged.

## Authority, exact failure and scope

Repository `cbattlegear/MeshCoreOne-Android`, issue135, existing draft249.
Direct parent `197df91907af69b613cd46c2a60b5fb580b41649` remains LOCAL.
Public head remains `779d78ab5aff7e1cd6ec636f988c0125709f098b`.
Main input `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen reference `db14559b39d32322b06477c6ae676112f583db50`;
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

Fresh source/policy/clean197/canonical-owner41 guards preceded these edits.
Both corrective paths already have trusted `wp-owned`/`modify` admission in
the SAME reservation41. No new path, evolution or reservation recreation.
Historical40 ->41 admission preceded the parent197 three-path implementation.
Same attempt/session `3bc8c1b8-20ec-4647-88ae-db3775268489`;
runtime alias `fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
No replacement reservation or changed historical authorization/bootstrap
binding. Fresh actor cbattlegear, remote mainDBB and OPEN/DRAFT249-at779/
baseDBB were read. Unpaused/off/activationfalse policy remains unchanged.
Retained comparator/native owner2be and acquisition1792555610.8776073 were
read only; no native lease action, hook/config installation or mutation.

The coordinator supplied official exact779 automatic39583539382 terminal
FAILURE: Linux119167427560 App848 discovered/846 passed/2 failed/0 errors/
skips; mandatory reports INCOMPLETE; aggregate119174153037 FAILURE;
SwiftData119174153946 upstreamSKIPPED, not scope-excluded or passed.
No polling, dispatch/rerun or duplicate CI-result download/hash/bundle.

| Failed AndroidOnly control | Supplied actual observation, not inferred success |
| --- | --- |
| mixedHebrewLatinLtrPaintMatchesActualCallbacksForContentCenterRightAndWrappedText | Reached actual nativeDerivedAgreement=passed at fixed-right-bidi: native260x24/paragraph260/line189.15625..260 and every UTF16 comparison passed BEFORE strict offset8 rejection at251.8..260.8 versus260. This confirms native caret agreement, not glyph ink/clipping. |
| mixedBidiRightAlignedCopyStillRequiresRealTargetViewportContainmentAndScrolling | Failed BEFORE comparison: rawLayout("bidi-viewport-text") used the merged tree; official error identified exactly one matching UNMERGED node. This is the new diagnostic lookup error, not viewport/geometry evidence. |

No named passing verdict is inferred for the other20 flow/control identities.
Failed779/630/47/090/781 remain failed; other previous receipts are untouched.
Emoji failure cause remains unresolved. Reaction product gaps, removed-
translation boundary, all27/native hold and physical/full-WP gaps remain.

Parent197's helper and receipt incorrectly required all mounted positives/
halos to equal allocation-clipped paint before establishing actual clipping.
That is a SOURCE assumption, not an executed result or evidence of overhang.
This direct corrective commit preserves197 history and replaces that
assumption with separate exact observations, without amend/rebase or push.

## Source-backed public native draw surface

Existing repository prior art, unchanged:
`android/ui-tests/src/shared/kotlin/com/meshcoreone/android/uitests/ConversationScreenCases.kt`
385-416 uses actual owning `decor.draw(android.graphics.Canvas(bitmap))`
on the Robolectric lane. Its DEVICE PixelCopy/hardware-window branch is
separate and is NOT used or claimed here.

Pinned first-party source entries were read in memory, not installed,
compiled, copied into the implementation or used as CI-result bundles:

| Source entry | SHA256 / relevant contract |
| --- | --- |
| ui-graphics-android1.10.6 commonMain/androidx/compose/ui/graphics/Canvas.kt | `e49893e2b7a341d0976bf2ef4835dd76332773c378b5d6e41c54d7bdc4872978`; public Canvas(ImageBitmap), save/translate/clipRect. |
| ui-graphics-android1.10.6 androidMain/androidx/compose/ui/graphics/AndroidImageBitmap.android.kt | `6b15cfeba7e8192fc2ac731a91eea8b1910dfdb6142525c54e5d72d22577651d`; public Bitmap.asImageBitmap wraps the SAME native bitmap, not a copied renderer. |
| ui-graphics-android1.10.6 androidMain/androidx/compose/ui/graphics/AndroidCanvas.android.kt | `858a71bc5c9bd0dbbe5df9632bd946c075dee7623db779a6dba794475f329970`; ActualCanvas targets android.graphics.Canvas(image.asAndroidBitmap()). |
| ui-text-android1.10.6 commonMain/androidx/compose/ui/text/MultiParagraph.kt490-517 | `793ba1ec967b66dd0b94f9baaa9c7a7b04b684f68c9f66c3812ecee40592edb4`; public paint delegates to its actual native paragraph instances with color/shadow/decoration/drawStyle. No text reconstruction or per-offset rectangle renderer. |
| foundation-android1.10.6 commonMain/androidx/compose/foundation/text/modifiers/TextAnnotatedStringNode.kt531-578 | `aec6edcdefd3f2506140c2b5e6b08f54d27047b60f8ff0305b272f93e268f944`; allocation clip ONLY when hasVisualOverflow && overflow != Visible, then actual cached MultiParagraph with selected paint attributes. |
| foundation-android1.10.6 commonMain/androidx/compose/foundation/text/modifiers/TextStringSimpleNode.kt421-472 | `17eb39f90cff510ba13784b699516f464f79c49633bcfc586815186a40f74e14`; allocation clip ONLY when layoutCache.didOverflow, then actual cached Paragraph paint with selected color/shadow/decoration/drawStyle. |
| material3-android1.4.0 commonMain/androidx/compose/material3/Text.kt101-146 | `f638817208f2ac7a6db976d085acba3cbdcee22c3eb2fb6f9cbf72ca5f570d65`; resolved local/style color is merged into the actual BasicText style, with supplied typography/align/callback. |
| ui-test-android1.10.6 androidMain/androidx/compose/ui/test/AndroidImageHelpers.android.kt | `9e1f556541d7578f2ae3e7ce40da3f13f9adc3cb6bed0d407054c6472eaccb0e`; captureToImage resolves a real window/captureRegionToImage, with separate popup screenshot behavior. This is NOT assumed to provide a supported hardware/OS screenshot in the existing Robolectric lane. |

Source archives:
`https://dl.google.com/dl/android/maven2/androidx/compose/<group>/<artifact>/<version>/<artifact>-<version>-sources.jar`.
Groups/artifacts are ui/ui-graphics-android, ui/ui-text-android,
ui/ui-test-android, foundation/foundation-android at1.10.6 and
material3/material3-android at1.4.0. Existing AndroidParagraph/TextLayout
caret-versus-ink trace in caret-native-diagnostics.md is preserved.

Both conditional clip statements and complete source-entry hashes were
independently re-read for this correction. Actual scroll/root ancestors can
add different clipping; no full native clip-chain model is inferred here.
The actual simple-string cache flag is not replaced by a reconstructed
semantics overflow flag or a callback twin's hasVisualOverflow. These SOURCE
contracts are distinct from the still-UNOBSERVED mounted raster outcomes.

## Diagnostic implementation and independent witnesses

`rawLayout(tag, useUnmergedTree = false)` preserves ALL previous default
behavior. Only the post-scroll original text explicitly opts into true.
Its actual native raster capture also explicitly selects that unmerged
text node, not a substituted target or fake semantics.

New test-only `MountedNativePaintObservations.kt` observes:

1. The ACTUAL callback MultiParagraph for fixed-right-bidi and the unchanged
   post-scroll96dp text, AFTER all native/derived exact input/line/every-
   UTF16 agreement assertions and BEFORE the original strict positive check.
   Fixed-right covers the existing LTR100/RTL200 parameterization without
   adding a family. The real post-scroll48dp/partial-rejection/scroll remains.
2. The ACTUAL cached AnnotatedString native MultiParagraph in the EXISTING
   center/right64dp clipping negative, before its original strict rejection.
   This is the real native semantics layout, not mountedPaintLayout output
   or another TextMeasurer render. No original six control bodies change.
3. A separate actual owning Android root-view `draw(Canvas(bitmap))` on the
   existing API31 NATIVE runner. This is mounted software-view draw output,
   NOT PixelCopy, an OS/display capture, hardware or physical-device evidence.

Each probe requires exact literal native text and allocation matching the
real mounted text node, then repeats existing real viewport containment.
The actual paragraph/fonts/shape/style are retained. Observed explicit
solid color is required; an unsupported brush fails explicitly. Actual
shadow/decoration/drawStyle or their source-standard null defaults are used.
There is no fallback text, typeface, layout, glyph/selection/caret renderer.

The same native paragraph paints into transparent raw and exactly allocation-
clipped bitmaps. Nonzero-alpha pixel counts and original-origin-relative
extents are measured from ACTUAL pixels, not line/UTF16/caret rectangles.
Both nonempty patterned raw text and retained native text are required.
Every interior pixel must remain identical; no allocation clip may leak.
The existing genuine negative additionally MUST lose native ink outside the
unchanged allocation: outside>0, changed>0, retained<raw. Empty bitmaps,
background-only captures or a negative that never clips cannot pass.

Only diagnostic buffers receive margins, exactly one observed line-allocation
height on each side, and enough integer storage for the actual paragraph/
allocation. ceil is solely bitmap-storage sizing, NEVER an oracle bound.
Finite/bounded paragraph and no probe-edge contact are required; truncated
offscreen ink fails explicitly. The translation places the original native
origin in this separate bitmap and is printed as probeOrigin. It never moves
the mounted text or changes any oracle coordinate. Pixel extents relative
to that original origin are DIAGNOSTICS, not adjusted UTF16 expectations.

Mounted pixels are read at actual node.positionOnScreen minus the real owning
root's screen location, with zero-tolerance integer-origin assertions. No
floor/ceil/subpixel normalization of mounted geometry. The unchanged text
allocation must fit the actual owning view, in addition to the unchanged
window/visible-frame/root/all-scroll/inset checks. A real transparent native-
ink pixel identifies a mounted background sample; failure to obtain one is
explicit, not an assumed white/transparent background.

The same actual native paragraph paints twice more directly over that SAME
observed background: one raw composite and one allocation-clipped composite.
Neither transparent-bitmap/blit quantization nor a selected favorable paint
path is introduced. Both hypotheses are compared against the SAME actual
mounted capture at zero pixel tolerance. Separate raw/clipped mismatch counts
are retained for EVERY allocation pixel AND the available left/right mounted
halo, one allocation-height wide within the real owning view. No-ink pixels
must agree too; no assumed background or empty capture can validate text.

The compact mountedPaintMatch field is descriptive, not an acceptance flag:

| Observed classification | Exact comparison meaning, NOT a predicted clip chain |
| --- | --- |
| raw-only | Zero raw mismatches in allocation AND observed halo; clipped has a mismatch. Matches raw paint in this sampled region only, not proof of no clipping everywhere. |
| clipped-only | Zero clipped mismatches in allocation AND observed halo; raw has a mismatch. Matches allocation-clipped paint here, not attribution to Text rather than an ancestor. |
| both | BOTH exact comparisons match. The observed pixels cannot distinguish the hypotheses; no actual clipping/absence of clipping is claimed. |
| neither | At least one mismatch for EACH hypothesis. Explicit capture/paint-contract assertion failure with both counters; never a fallback or product/font verdict. Ancestor clipping, style/origin/background/capture differences still require diagnosis. |

No model is chosen to feed the oracle, renderer, subsequent checks or expected
text/UTF16 geometry. The original strict positive checks still follow this
diagnostic unchanged for ALL non-neither observations; raster comparison
does not confer acceptance or waive the still-strict caret rejection.
There is no retry using a different model, tolerance, least-mismatch choice,
normalization or substituted expected image.

The genuine negative retains raw offscreen outside>0/changed>0/retained<ink
AND now requires actual mounted classification EXACTLY clipped-only. Thus
the actual observed region must distinguish lost native ink: raw must
disagree while clipped agrees everywhere sampled. both/indistinguishable
CANNOT certify a clipping witness; raw-only/neither also fail the negative.
The original line-bound rejection still follows unchanged. This witnesses
effective mounted clipping in the sampled region IF actually executed, not
which Text/ancestor caused it and not the entire unseen paint/clip chain.

The halo's actual X interval and BOTH mismatch counts are reported. Probe
intersection with the owning view limits only diagnostic sampling, never
visibility/geometry acceptance. Unsampled exterior and vertical halo pixels
are NOT claimed observed. Both comparisons and all four classifications
remain UNEXECUTED; no outcome or physical/font/raster acceptance is inferred.
Native/software mismatch is a concrete observation-contract failure, not a
permission to loosen the original oracle.

Every successful bitmap allocation is immediately registered inside a
try/finally-owned scope: raw, clipped, mounted capture and BOTH composites.
The finally block recycles all registered buffers on normal return, assertion
failure, native draw/paint failure or partial allocation failure. Native
canvas save/restore also has try/finally. No catch hides primary assertions.

Compact raster counts precede retained callback geometry in the two positive
failure contexts ONLY after all raster assertions succeed. Separate raw and
mounted source labels prevent offscreen output from masquerading as mounted
capture. No PNG, golden update, saved bitmap, image hash or CI-result bundle.
Whole native paragraph pixels are not per-glyph attribution and never
replace the every-UTF16 guard. No geometric interpretation is changed.

## Complete preservation and source checks

Exactly TWO paths change from197: the raster helper and this receipt.
The cumulative delta from779 remains the SAME three paths; the entire
control file, including the unmerged finder repair/defaults, is exact197.

| Test file | Parent197 blob | Corrective successor |
| --- | --- | --- |
| MountedNativePaintObservations.kt | `110c2183f081b29b9dbbea2d3d1460e472381df2` (155 lines) | `e923c56e5b8eb564bd886ba76dd67fca7138dc63` (178 lines); dual observations/classification/finally cleanup only |
| MountedTextAssertionsTest.kt,370 lines | `3cd23065f5784ee51cb84cdabee660fe5bd2e88b` | ENTIRE FILE UNCHANGED; outside the two-path delta |
| MountedTextAssertions.kt,187 lines | `3f2ebe3b94eb0669df33c050dfd493250fc1c214` | ENTIRE FILE UNCHANGED; outside the two-path delta |

Reversing ONLY the reviewed dual-composite/comparison/diagnostic block and
finally-owned allocation/canvas cleanup restores the ENTIRE197 helper,
SHA256 `635b1c94984c67f90710b220f7bbfad9cb58bd289ddaf51ec10d9eae2bd54913`.
All input/full-text/nonempty/ink/interior-clip/geometry/viewport predicates
outside that reviewed raster block are byte-exact after deindentation.
Reversing ONLY the parent197 finder/probe/context additions still restores
the ENTIRE779 control file,
SHA256 `49500699c35b6f2b44d57e7558e104cdd39b424634bc8ebb26e244b81320076b`.
Complete six-old-control tail exact; all five existing new families and all
eleven definitions retained, no new Test/OriginalCase accounting.

Entire NewChat`f858c733eec71bc64a7eaf8d9e83b0cae71458ed`,
Emoji`b6ddc2eeae41cac40d4fb664525fa21a8db17a54`,
Reaction`ff3add425a6026eaa48d92d08704be60fc3ccaca`,
ContactMatch`ca8a9d0b7915f01d4628acb03d06d1997f8052ee`,
Rooms`276317f4fe7521fe4c016bc55357372c85e09278`, all OTHER historical WP502
evidence/deviation and App lock`c4d5b74acc0efef9f4162c95d843c4c2c1186ce7`
remain exact. All11 full flows/literals/read/service/MRU/caret/cancellation/
locale/theme/font/090scroll/ordering/48dp predicates are untouched.
All34 independent provenance bindings freshly match:16 db145/779/197/local
frozen inputs and18 mainDBB/779/197/local native inputs. Policy/manifest unchanged.

```text
python tools\android-port\portmap.py              PASS1737 mappings
python tools\android-port\controller\validate.py  PASS1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
git diff --check                                 PASS
```

Complete reversal, six-old-control tail, eleven identities, exact incumbent
source/docs/lock and all34 binding checks PASS as SOURCE checks only.
An initial source comparison incorrectly compared physical Windows CRLF
control bytes to git-show LF bytes and failed at the six-control tail.
The actual unchanged Git blob remained3cd23065f5784ee51cb84cdabee660fe5bd2e88b;
all370 working lines were CRLF. The corrected canonical-LF source comparison
matches the complete old tail. No control file, geometry or assertion was
changed to repair that comparison; this was not a Kotlin/runtime result.
The extra mapping is AndroidOnly support, not discovery or original coverage.
There remain22 expected flow/control definitions:3NewChat+4Emoji+4Reaction+
11controls. Future own hosted evidence still requires actual positive
identities, clean nonempty App, complete mandatory reports and own all-six
outcomes. Failed-only output cannot establish the remaining named passes.

**NOT RUN:** compiler/Gradle/WSL/native/device/host readiness or runtime probes,
lease/reentry/release/restart, hook/main/policy/dispatch/rerun/merge/settings
actions. No publication, new worker/family or expectation lowering. Stop
at ONE LOCAL successor for complete source comparison. Actual caret/ink/
mounted clipping results and geometry adaptation remain UNOBSERVED/BLOCKED.
