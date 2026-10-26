# WP-000 operation-bound advisory transfer evidence

`<!-- android-port-wp:WP-000 -->`

This follow-up hardens the advisory transfer helper without changing product behavior or granting
successor authority.

## Change

- `CapabilityEngine.transfer_intent` requires the capability operation and fails closed for unknown
  capabilities, unsupported operations, disallowed producer WPs, and out-of-scope paths.
- `wp-owned` transfer requires the same assigned WP and trusted manifest-assigned paths. The local
  CLI supplies those paths from trusted authority and requires `--operation`.
- Transfer output remains attribution-only; it does not change a reservation or admit a successor.
- Regression coverage fixes nested test-variable shadowing and asserts the explicit unknown-capability
  diagnostic.

## Binding

| Field | Value |
| --- | --- |
| Repository / work package / owner | `cbattlegear/MeshCoreOne-Android` / WP-000 / `port-orchestrator` |
| Session / branch | `fbbcfbcc-1115-4f05-94f9-9109c347734a` / `cbattlegear-improved-train` |
| Base | `61fc863172e9248f3ba913acefeb6fd2354d2580` |
| Reference source | `db14559b39d32322b06477c6ae676112f583db50` |
| Manifest | raw SHA-256 `949598d9d070f14378dcda6150f67ead1bb70d29fc261c685cadf6649c4aa175`; validator digest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8` |
| Policy revision | `46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8` |
| Issue / task / PR | No issue or task identity is asserted. Prior WP-000 PR #319 is merged; this follow-up is tracked by draft PR #326. |
| Implementation commit | `93e73d747d63f024d470640b12759a4799cdf73d` |
| Reservation | Existing attempt `5a89df75-a4ef-4cea-8c84-daa127f99f5e` belongs to another session/worktree/base. A fresh claim was blocked on that old binding; it was not treated as acquired. No ledger mutation occurred. |

## Verification

| Command | Result |
| --- | --- |
| `python tools/android-port/controller/validate.py` | Passed: 1,866 tracked reference files, 64 WPs, 178 dependency edges. |
| `python tools/android-port/controller/test_runner.py` | 551 discovered/run; 549 passed, 0 failed, 2 errors. Both errors are `verify_committed_inputs` checks caused by the preserved pre-existing `.github/skills/android-port-wp/SKILL.md` worktree edit. No Android/iOS/device tests are included in this result. |
| `python tools/android-port/local/reserve.py admit --wp WP-000 --path <changed path> --operation modify` | Passed for each changed code/documentation path; each returned `wp-owned`. |
| `git diff --check` | Passed. |

The local pre-push runner marked implementation commit `93e73d747d63f024d470640b12759a4799cdf73d`
UNVERIFIED after its selected stages returned nonzero; this is not a hosted result or a second result
bundle. Draft PR #326 retains that candidate state. The full WP-000 acceptance is not claimed. The
candidate does not change or verify Android product behavior, activate dispatch, mutate reservations,
admit a successor, or satisfy protected review.

## Follow-up: hosted Kotlin declaration binding

The earlier verification and draft state above are historical. PR #326 subsequently became ready
for protected review at `22d0d15cd690338e28cd92a402e7b019e227bd74`; this parser follow-up changes
that candidate and requires fresh exact-head review/checks.

The bounded repair publisher rejected two genuinely executed PR #327 paging methods because their
attached `OriginalCase` strings contain `()`. Its old `[^)]*` annotation-argument expression stopped
at the quoted closing parenthesis. It also mistook a quoted `@Test` inside a non-JUnit annotation
for a real JUnit annotation.

The declaration reader now skips comments and complete Kotlin literals/templates, balances nested
annotation arguments, and requires `Test` or `org.junit.Test` on the selected function itself.
Backtick names and the existing public/internal forms remain supported. Exact candidate blob,
verified module and actual executed class/method checks are unchanged; no selected method is dropped.
The controller-local reader avoids importing the separate WP-501 analysis tool into the installed
controller runtime. Neither that tool nor its accounting behavior changes.

Both code/test paths and the three directly related documentation paths returned `wp-owned` for
WP-000 / `modify`. The separate attempted shared-lexer extraction was not admitted for
`android/parity-tests/semantic.py` / `modify`; that path was not changed and no new helper was added.
The historical reservation failure remains unchanged and is not an acquired claim.

- `python tools/android-port/controller/validate.py`: passed, with 1,866 tracked reference files,
  64 WPs and 178 dependency edges.
- `$env:PYTHONPATH=(Join-Path (Get-Location) 'tools\android-port'); python -m unittest discover
  -s tools\android-port\tests -p test_repair_acceptance.py -q`: 19 tests passed.
  These include valid quoted/escaped/raw/template
  strings, nested arguments/comments and backtick names, plus rejection of comment/string spoofing,
  detached annotations, non-JUnit annotations and malformed syntax.
- The candidate parser, applied in memory to the existing exact-head PR #327 source blobs and
  authoritative run `40284686761` / job `121446689592` execution inventory, bound all three selected
  methods: queued-spinner retirement, successful prepend/anchor preservation and failed-paging
  passive error. The inventory contains 392 `feature/chats` methods. No second CI-result bundle
  was retained, and this does not certify the revised PR #326 or deploy its parser.
- The declared strict controller runner is pending for this follow-up. The preserved local skill
  overlay remains present; no prior-head check or review is carried forward as new-head approval.

No original-family credit, full-WP acceptance, publisher deployment, protected merge approval,
runtime installation, native execution or fleet activation is claimed.
