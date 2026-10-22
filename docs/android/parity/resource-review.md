# WP-501 localization resource comparison

Source remains `db14559b39d32322b06477c6ae676112f583db50`. This is a
source/native/resource/helper comparison proposal, not independent acceptance,
iOS execution or a claim that every UI consumer has been reviewed.

## Inputs and observable contract

The actual WP-005 converter reads all120 OpenStep `.strings`,48 `.stringsdict`
and the AppShortcuts catalog from the frozen Git tree. The source/key/blob/value
records in `docs/android/generated/l10n-key-map.json` bind all169 inputs,
28,903 localized records and2,406 distinct keys. The exact32 billing exclusions
leave2,374 native resources:2,363 strings and11 plurals. No message-translation
provider/model, new excluded key or revised source pin is introduced.

Reviewed parser behavior includes quoted delimiters/comments, UTF8/UTF16 BOMs,
escaped Unicode/quotes/backslashes, real whitespace/newlines, duplicate-key
rejection, plist entity/DTD rejection and explicit unsupported-input failures.
The twelve locale mappings retain Simplified Chinese and Portuguese pt-PT
per-app choice with the source pt fallback. Exactly seven missing Italian
shortcut phrases use the pinned English fallback; existing English-equivalent
translations are not falsely classified as missing.

## Actual native assertions and adaptations

`LocalizationResourceTest` iterates actual AAPT-compiled values for every
static and formatted source record in every locale. Its expected text comes
from pinned raw-source records rendered by test-only `referenceText`, not
candidate `L10nFormatting`. Argument samples contain CJK, quotes, percent,
XML characters and newlines. The test additionally checks long German copy,
Chinese copy and locale XML/scaffold aliases.

All eleven plural resources exercise0/1/2/3/4/5/11/12/14/21/22/24/25/101/102/
111/112/one-million/signed-Int limits, plus signed-Long limits and values above
Int.MAX_VALUE where the displayed argument is Long. The independent test
`integerQuantityCategory` retains Polish/Russian/Ukrainian one/few/many,
Korean/Chinese other and French/Portuguese variant behavior. The selector never
narrows the displayed number. Partial node removal selects on total argument2,
not removed argument1.

`L10nFormattingTest` separately checks current-resource-locale decimal formatting,
nonfinite-number rejection, signed64-bit display, large selectors, pt-PT/pt-BR,
named shortcut/intent arguments, unknown-language English fallback, literal
argument preservation and explicit missing-resource exceptions. The sole
approved Chinese comparison-integer correction is fingerprint-bound in
`localization/format-adaptations.json`; surrounding source copy remains unchanged.
It is not general permission to repair translations.

The two actual original localization declarations are individually compared
in `review-proposals.json`: exact English `Airtime %` and all59 subdivision
keys in all twelve locales. Native checks strengthen the latter with actual
source-value equality, unknown-code null and immutable catalog snapshots.
Their precise renamed JUnit4 identities are bound, without inflating loop
iterations into original declaration counts.

## Execution and consumer boundary

Actual local `python tools\android-port\l10n_convert.py --check --self-test`
passed74 Python cases with all63 generated files,169 inputs,12 locales,
2,374 resources and no unsupported inputs. This verifies resource/parser/
conversion bytes, not new native runtime execution. The current candidate's
four native localization suites and exact original cases still require their
actual JUnit execution; historical green totals are not promoted.

Typed generated ids/functions accept current Resources. Widget Long plurals,
shortcut named arguments and remote status labels are exercised by real
typed calls in those tests. Remaining feature-wide current-language changes,
screen layout/accessibility and platform invocation behavior remain separate
consumer audit/device work; compiled resource equality alone does not prove them.
