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
| Issue / task / PR | No issue or task identity is asserted. Prior WP-000 PR #319 is merged; this candidate has no PR yet. |
| Reservation | Existing attempt `5a89df75-a4ef-4cea-8c84-daa127f99f5e` belongs to another session/worktree/base. A fresh claim was blocked on that old binding; it was not treated as acquired. No ledger mutation occurred. |

## Verification

| Command | Result |
| --- | --- |
| `python tools/android-port/controller/validate.py` | Passed: 1,866 tracked reference files, 64 WPs, 178 dependency edges. |
| `python tools/android-port/controller/test_runner.py` | 551 discovered/run; 549 passed, 0 failed, 2 errors. Both errors are `verify_committed_inputs` checks caused by the preserved pre-existing `.github/skills/android-port-wp/SKILL.md` worktree edit. No Android/iOS/device tests are included in this result. |
| `python tools/android-port/local/reserve.py admit --wp WP-000 --path <changed path> --operation modify` | Passed for each changed code/documentation path; each returned `wp-owned`. |
| `git diff --check` | Passed. |

The full WP-000 acceptance is not claimed. The candidate does not change or verify Android product
behavior, activate dispatch, mutate reservations, admit a successor, or satisfy protected review.
