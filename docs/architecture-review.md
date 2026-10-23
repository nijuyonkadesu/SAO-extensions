# Architecture review — October 2026

A dedicated Sol 6.1 xhigh review covered all first-party production Kotlin/Java/C++ sources, manifests, build configuration, settings/providers and release tooling, excluding tests. A subsequent focused review checked the new shared runtime. Regression tests use synthetic data and localhost HTTP; no personal account or server was inspected.

| Priority | Finding | Resolution |
|---|---|---|
| P1 | Local scan completion could prune a different root or a newer scan generation | Root/generation membership, guarded completion/pruning, and schema v9 migration preserving assets/corrections/vectors |
| P1 | SAF row offsets could skip files after an unordered listing changed | Resume from the beginning, skip unchanged visited entries, guarantee progress before yielding, and require EOF before completion |
| P2 | Provider pipes could leave stalled upstream requests running after the host stopped reading | Shared cancellation-aware reliable socket transport with a semaphore spanning producer suspension; peer close cancels queued work and active upstream I/O |
| P2 | Settings edits and credentials were lost on recreation | Stable draft IDs, local SavedStateHandle drafts, and encrypted lifecycle-bound Immich/Moeview/Google drafts outside saved-state bundles |
| P2 | Browser verification returned cookies through Activity result state | Encrypted request/profile/board-bound handoff; only an opaque token crosses the result boundary |
| P2 | Metadata refresh retained obsolete matches and stale continuation order | Reconcile the loaded window against current query/order before continuing |
| P2 | Immich pages and exact-choice responses could exceed Binder budgets | Budget actual UTF-16 wrappers; authenticated query/profile/configuration-bound continuations retain every unconsumed row; reject changed/repeated pages |
| P2 | Cancelling a Google login could leave the chooser/token request alive | Nonce-scoped remote cancellation, binder-death cleanup, and acknowledged completion; stale results cannot background Photos |
| P2 | Release tooling could publish old outputs under a new commit | Rebuild all release inputs and verify clean commit/artifact provenance before publication |

## Boundaries retained

`extension-runtime` shares only stream lifetime and private draft storage across APKs. It adds no runtime dependency beyond the existing source SDK. It owns no media cache, pagination scheduler, backend configuration or indexing policy. SAO remains responsible for browsing/cache/delivery and host upload scheduling; extensions retain API negotiation, credentials, local grants/models/indexing and backend limits. The Binder protocol remains v1; no SDK artifact or catalog publication is needed for these changes.

Draft persistence runs on IO at lifecycle boundaries, not on every text edit. Android saved-state bundles carry only nonsecret identities/options/references. AES-GCM records use AtomicFile under noBackupFilesDir; request-bound authenticated data protects private handoffs. Cancelling a completion handle does not break checkpoint ordering or resurrect a discarded draft.

Immich random results are one finite batch: optional presentation fields can shrink to fit, but identities/filenames are never truncated, rows are not dropped, and a second random request never pretends to continue the first batch. Exact unpaginated filter/place lists that cannot fit fail with LIMIT_EXCEEDED instead of silently changing searchable values. Search pages may refetch the same backend page with a validated offset; they do not retain a previous-query results cache. A changing page invalidates that cursor explicitly rather than silently skipping rows.

Moeview’s upstream request gate remains in the extension because it implements board rate limits; it does not choose when SAO browses or fetches a page. Configured six-hour folder reconciliation remains Android-scheduled work. Neither an idle floating icon nor a media stream introduces a periodic polling loop or custom wakelock.

## Validation

All five extension APKs build in debug and optimized release variants. All 69 extension JVM checks pass, and every module’s debug lint reports zero errors (warnings remain). Fifty-seven distinct Android 15 instrumentation checks pass:

| Area | Checks |
|---|---:|
| Shared stream/draft lifetime | 12 |
| Immich bounded paging and exact-value overflow | 7 |
| Immich private draft restoration | 3 |
| Local scan recovery, migration, drafts and metadata refresh | 7 |
| Moeview opaque browser-verification handoff and draft restoration | 2 |
| Google Photos cancellation, private drafts and synthetic API/media integration | 26 |

The tests cover stalled upstream reads, blocked socket writes, closed/cancelled queued transfers, suspension-wide transfer caps, encrypted checkpoint ordering, unordered/mutating SAF listings, stale scan generations, root-scoped pruning, retained corrections/vectors, changed metadata membership, query/profile-bound cursors and original-byte playback separation. One new paging fixture incorrectly expected trailing search whitespace; it was corrected to the existing compiler contract and its test passes. No production query semantics changed for that correction.

The host additionally passes 51 JVM and 60 distinct Android checks; its debug/release builds and lint pass. No APK, GitHub release or catalog is published by this review. Live Google/Immich accounts, third-party service behavior and physical Vector injection remain outside these synthetic checks.
