# Connection runtime

This module is part of the GPLv3 MeshCore One application. The application's
[`LICENSE`](../../../LICENSE) governs the runtime, including ported connection
error prose. It is not MIT protocol code. The separately licensed
`:core:protocol` dependency retains its own MIT notice.

Production dependencies are only `:core:protocol`, `:core:model` and
`:core:contracts`. Android persistence/BLE/CDM/FGS adapters and the concrete
WPs 208-218 graph are constructor/factory inputs supplied at WP-303.
No production test graph or default-success service is available.

`FactoryOwnership` must own allocations immediately and register the returned
handle before suspension. Generation-specific callbacks and scopes end with
that handle. Process preferences/repositories are never closed on a radio
disconnect. A retained physical link is recovery state, not a fresh protocol
generation; reacquisition awaits its real physical close.

Declared verification uses `:core:runtime:test`,
`:core:data:testDebugUnitTest`, and `:core:datastore:testDebugUnitTest`
directly. The owning module hooks join existing root `verifyScaffoldTests`;
no shared root task or production graph edge is added.

Equivalent callers observe one submitted attempt; only its authoritative
revision owner may cancel it or clear its pending slot. A late diagnostic
failure remains visible to that caller without invalidating a successfully
completed shared generation. Cleanup always joins the owned operation and
retains original and suppressed failures.

The consolidated candidate build runs the runtime, data, and datastore tests
directly. It does not produce a post-test runtime evidence directory or retain
a second copy of reproducible CI results. Historical evidence never becomes
current-head acceptance merely because file names or test counts are unchanged.

See [WP-207 evidence](../../../docs/android/evidence/WP-207/README.md) and
[native adaptations](../../../docs/android/deviations/WP-207.md) for exact
source revisions, original-family accounting and execution limits.
