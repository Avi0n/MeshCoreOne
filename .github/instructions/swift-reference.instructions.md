---
applyTo: "MC1/**,MC1Services/**,MC1Tests/**,MC1Widgets/**,MeshCore/**,Shared/**,AppIcon.icon/**,LICENSE,project.yml,swiftgen.yml"
---

# Read-only Swift reference

These paths are specification inputs, not Android write targets. Read their current behavior and
associated tests/resources at `db14559b39d32322b06477c6ae676112f583db50`; do not edit, regenerate,
reformat, translate, update goldens, install packages into or advance this tree during port work.

The manifest assigns exact primary ownership and cross-references, including errors/extensions/helpers,
widget localization/plurals, themes/icons/styles and license inputs. An explicit file exclusion removes
Apple glue/generated output/billing, not associated required behavior. The user-approved message
translation removal has exact `removed-translation` source/test/helper exclusions; mixed messaging,
localization, backup and rendering inputs remain in scope. No future build requires translation.

Use a later actual Swift/macOS oracle for backup compatibility. Report newly discovered or changed
sources to WP-006 as unassigned blockers; do not infer that a header or filename proves acceptance.
