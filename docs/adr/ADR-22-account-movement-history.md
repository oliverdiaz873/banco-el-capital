# ADR-22: Account Movement History

## Context

Deposit (`docs/deposit-design.md`), Withdrawal (`docs/withdrawal-design.md`) and
Transfer (`docs/transfer-design.md`, ADR-18) persist, on confirmation, immutable
`movements` rows that are the authoritative evidence of financial effects
(ADR-15/ADR-16/ADR-18). ADR-03 allows a holder to consult their account, and
ADR-11 states that history serves customers querying operations and movements.
Yet no read exists today beyond `GET /api/v1/accounts/{id}` (account state plus
stored balance) and the per-operation outcome queries keyed by
Idempotency-Key (`GET /api/v1/{deposit,withdrawal,transfer}-operations/{key}`).
A customer cannot list the effects on their account over time. The
account-lifecycle design document records this gap as pending decision P5
("`GET` account scope - minimal read model vs full history").

This ADR defines a read-only movement history for one account. It introduces no
write, no balance change, no new financial operation and no new persistence
entity. It does not design authentication beyond the current stub (ADR-09),
observability (ADR-12), beneficiary aliases in history, retention, exports, or
any distributed concern.

## Decision

Adopt a read-only account movement history as a Financial Operations read
capability:

- The resource is the list of `movements` whose `account_id` is the requested
  account. Each row is one confirmed immutable financial effect (a `DEBIT` or a
  `CREDIT`), never an intent.
- Only effects of CONFIRMED operations exist as `movements`; rejected or failed
  attempts have no movement and are therefore not represented. The history is a
  record of confirmed effects, not a record of attempts.
- A transfer appears as its two effects: one `DEBIT` on the source account's
  history and one `CREDIT` on the destination account's history, both carrying
  the same `operationId`. The history never presents a single transfer twice
  within one account.
- The endpoint is `GET /api/v1/accounts/{accountId}/movements`, with `limit`
  (default 20, maximum 100) and an opaque `cursor`.
- Ordering is `created_at DESC, id DESC`.
- Pagination is keyset (cursor-based), not `OFFSET`.

## Contract and Authorization

Method, path and headers:

- `GET /api/v1/accounts/{accountId}/movements?limit=<n>&cursor=<opaque>`.
- The actor is identified with the existing `X-Actor-Id` header, exactly as the
  current endpoints do. No `Idempotency-Key` is required: reads are not
  idempotent intents and the existing outcome queries also require none.

Each item carries exactly:

- `movementId` (UUID), `operationId` (UUID), `operationType`
  (`DEPOSIT`|`WITHDRAWAL`|`TRANSFER`), `direction` (`DEBIT`|`CREDIT`),
  `amountMinorUnits` (integer), `currency` (ISO-4217, 3 letters),
  `createdAt` (instant), `counterparty`.

Authorization reuses existing public contracts only:

- The holder is server-resolved through
  `accounts.api.AccountLookupService.findHolderCustomerId`.
- The decision uses `identity.AuthorizationService.decideViewAccount`, which
  allows the holder acting for themselves or an actor carrying the
  `BANK_EMPLOYEE` role.
- The read never touches `accounts.internal` or any other module's internals
  (ADR-01/ADR-17).

The actor is read directly from the existing `X-Actor-Id` header; the current
code has no separate authentication filter, so a missing or blank header yields
an unauthenticated actor. The endpoint introduces no new authentication
semantics and reuses the existing mappings: an unauthenticated actor on an
existing account maps to `401` (the existing UNAUTHENTICATED mapping), and an
authenticated actor who is not authorized to view an account maps to the same
safe `404` as an unknown account. This guarantee does not apply to
unauthenticated callers; the inherited `401`/`404` asymmetry is documented
below. The order of the account-existence lookup relative to the authorization
decision MUST mirror the existing account read (`GET /api/v1/accounts/{id}`) and
be confirmed against the current code before implementation.

The unauthenticated path (`401` on an existing account versus `404` on a missing
one) is an inherited behavior of the current read path. It is documented here,
but it is NOT an effective privacy protection for unauthenticated callers, and
correcting it would be a transversal change outside the scope of this ADR.

## Invariants (Privacy and Financial)

- Read-only: the capability MUST NOT create, update or delete any row; no
  movement, idempotency record, balance or audit change.
- The response MUST NOT contain any account identifier, including the requested
  `accountId`, and MUST NOT contain personal data of any counterparty. The
  requested `accountId` is used only as an internal query filter and is never
  echoed back in the returned items.
- `counterparty` is `null` for `DEPOSIT` and `WITHDRAWAL` (there is no
  counterpart account in the model). For `TRANSFER` it is a generic,
  non-identifying descriptor whose `type` identifies that the effect belongs to
  a transfer. The raw destination/source account id of the other party is NEVER
  exposed; the model has no counterparty column, and no third-party id is
  reconstructed for the response.
- The beneficiary alias snapshot is intentionally excluded from this increment,
  to avoid coupling a Financial Operations read to the beneficiary evidence
  table; including it would require a cross-feature read and is deferred.
- Amounts are integer minor units; `currency` is always explicit; no
  float/rounding is introduced.
- Internal states forbidden by ADR-10 (such as DEBITED/CREDITED/PARTIAL) MUST
  NOT be exposed. The contract returns only the fields expressly enumerated
  above; in particular it carries no `status` field, and `direction` is the only
  state-like value it exposes.

## Pagination Semantics

- Sort key is the pair `(created_at, id)`, ordered `created_at DESC, id DESC`;
  `id` makes the order total and stable when `created_at` ties.
- The `cursor` is an opaque token encoding the last item's `(created_at, id)`.
  `created_at` MUST be encoded as a signed 64-bit integer count of microseconds
  since the Unix epoch, taken from the stored `TIMESTAMPTZ`; `id` is the
  movement UUID. The token is serialized opaquely with Base64URL. On decode the
  implementation MUST validate the structure, the temporal range and the UUID
  before querying, and MUST NOT reconstruct `created_at` through any conversion
  that reduces precision (seconds or milliseconds). Exact microsecond precision
  MUST be preserved across PostgreSQL, JDBC and Java; this is an implementation
  acceptance requirement, not merely a recommendation. The microsecond count is
  derived from the stored instant by exact conversion (no truncation, no
  rounding), and the SQL predicate compares the same instant and the same
  movement UUID that the cursor encodes. A malformed or incoherent cursor is a
  client error (`400`). The cursor is opaque and validated, but it is NOT
  encrypted and NOT signed/authenticated; callers MUST NOT treat it as a secret
  or as proof of anything.
- A page is selected with the row-value predicate
  `(created_at, id) < (cursor.createdAt, cursor.id)` (strictly older than the
  cursor), then ordered and limited; the implementation requests `limit + 1`
  rows to decide whether a `nextCursor` exists.
- The row-value predicate above is the architectural description. The
  implementation expresses the same predicate in a portable explicit form,
  `(createdAt < :cursorCreatedAt) OR (createdAt = :cursorCreatedAt AND id < :cursorId)`,
  to avoid engine-specific row-value syntax. This is an equivalence of
  expression, not a change of decision.
- `limit` defaults to 20 and MUST be at most 100; values outside the accepted
  range are rejected with `400`.
- Consistency limit (binding wording): the history is NOT a single consistent
  snapshot. Keyset pagination uses the immutable ordering key `(created_at, id)`
  and prevents already-traversed rows from being repeated solely because newer
  rows are inserted: because ordering keys are immutable, a row once returned
  cannot move relative to the cursor. It does NOT provide a consistent snapshot
  across page requests: rows committed concurrently may appear on later pages
  when their ordering key falls beyond the current cursor, and the guarantee
  relies on movements being immutable (the model never updates or deletes
  them). No snapshot isolation, no "as-of" timestamp and no locks are
  introduced by this decision.

## Read Transactions

- The read runs in a read-only, short-lived transaction, consistent with the
  existing read paths; it takes no row lock and mutates nothing.
- No isolation level stronger than the database default is required for
  correctness; the pagination guarantees above are explicitly limited and do
  not rely on snapshot semantics.

## Consequences

- What becomes easier or more possible: holders and employees can observe an
  account's confirmed effects over time; the effect records already persisted
  are finally queryable; a base exists for future statements and reconciliation
  views.
- What becomes harder or more difficult: a new public read contract to keep
  compatible; pagination and privacy rules must be preserved by future
  consumers; the intentional exclusion of rejected attempts may surprise
  clients that expect an "operations" list.
- Trade-offs: exposing only confirmed effects (faithful to the evidence model)
  versus an operations view that also shows rejections (deferred); generic
  counterparty descriptors (privacy-safe) versus revealing the counterparty
  account id (rejected).

## Alternatives Considered

- Operations history (all intents, including REJECTED/FAILED) -> Deferred: mixes
  intent and effect, contradicts the evidence-only nature of `movements`, and
  would need the intent/idempotency records; a separate resource if ever
  required.
- Exposing the counterparty `accountId` -> Rejected: it belongs to another
  customer and would leak a third-party account identifier (ADR-10).
- Including the beneficiary alias in the MVP -> Deferred: it would couple this
  read to the beneficiary evidence table across features; excluded to keep the
  dependency surface minimal.
- `OFFSET` pagination -> Rejected: unstable under concurrent inserts (skips and
  duplicates).
- A materialized/denormalized statement projection -> Rejected for this
  increment: no evidence justifies extra storage; the existing tables suffice.
- Auditing reads -> Rejected for the MVP: reads are not financial effects and
  ADR-11 separation keeps history, audit and logs distinct.

## Relationship to Existing ADRs

- ADR-10: adds one versioned, safe REST read; no API-principle change. The
  safe-error principle is preserved: for this endpoint an unknown account and an
  account the actor may not view are indistinguishable, both returning the same
  `404`. This is stricter than the existing account read, which returns `403` to
  an authenticated non-owner.
- ADR-11: serves the stated purpose that history lets customers query
  operations and movements; introduces no new audit event and adds no read
  auditing. No audit-principle change.
- ADR-15/ADR-16: reads the stored `movements` as the authoritative evidence of
  effects; does not assert balance = sum(movements) as a physical rule. No
  representation change.
- ADR-17: the read lives in Financial Operations and depends only on
  `accounts.api` and `identity`; no internal access, no new shared module, no
  packaging change.
- ADR-18: reads the movements produced by transfers (Option A); does not change
  transfer semantics, lock ordering or atomicity.
- ADR-19: complements the minimal account read; does not change lifecycle.
- ADR-01/ADR-02/ADR-04/ADR-06/ADR-07/ADR-08/ADR-09/ADR-13/ADR-14/ADR-20/ADR-21:
  no change; the capability is read-only and reuses existing contracts.
- No existing ADR's status is changed by this decision.

## Deferred Index

No index currently exists to support "movements by account ordered by date":
`movements` and `financial_operations` are created without an index on
`account_id` or `created_at` (inspection of the V5 migration). A supporting
index (candidate: `movements(account_id, created_at DESC, id DESC)`) is
DEFERRED and MUST NOT be created as part of this decision. It may be
reconsidered only with evidence: an `EXPLAIN`/`EXPLAIN ANALYZE` on PostgreSQL
with representative volume showing that the history query degrades
unacceptably. Correctness does not depend on it. No V12 is created or required
by this ADR.

## Status

Accepted - approved at the architecture/documentation level. Implementation
required a separate, explicitly scoped approval; that approval was later granted
and the capability is now implemented and merged (see "Post-implementation
note"). All documented limitations remain in force: no cross-page consistent
snapshot, the inherited unauthenticated `401`/`404` asymmetry, the deferred
supporting index (no V12), and no read auditing.

## Post-implementation note (2026-10-18)

This note is appended after the fact. It does not amend the decision above, its
date or its authorization history; it records what was implemented and verified.

- Implemented and merged via PR #12 (`feat(financialops): add account movement
  history`), feature commit `646dec8cac34947164f9078ac389557ff0a51df4`, merge
  commit `19555bae91a86796792b1fa7671be1977a613e2f` on `main` (merged
  2026-10-18).
- Verified in CI on the merge commit: build job green with 419 tests, 0 failures
  and 0 skipped; PostgreSQL gate green and fully executed with 80 tests over 24
  classes, 0 skipped and 0 failures, reporting "all executed, none skipped, none
  failed".
- The order of the account-existence lookup relative to the authorization
  decision, which this ADR required to be confirmed against the code before
  implementation, was confirmed: the service resolves the holder first and only
  then decides, mirroring `AccountTransitionService.findAccountView`.
- The implementation expresses the keyset predicate as a portable explicit form
  equivalent to the documented row-value predicate (see "Pagination Semantics").
- The response exposes no account identifier at all, including the requested
  `accountId` (see "Invariants").
- NOT verified end to end: the `BANK_EMPLOYEE` authorization path is not
  exercised through HTTP. The web layer builds the actor with an empty role set
  and the API carries no role transport, so no HTTP test demonstrates that
  propagation; the role is honored only by the authorization contract itself.
- No supporting index was created, there is no `V12`, and the optional `EXPLAIN`
  evidence mentioned in the companion plan was not captured.

## Notes

- Language: MUST for decided architectural rules; SHOULD for recommendations;
  MAY for future possibilities.
- Evidence basis: `movements` and `financial_operations` schema (V5),
  `transfer_operation_idempotency` (V8/V11), `beneficiaries.api` and
  `accounts.api` contracts, and the `identity` authorization stub, as inspected
  at `origin/main` `5f66165` (historical: that was the revision current when this
  ADR was written; the increment was merged later at `19555bae`). No capability
  is assumed beyond what those artifacts provide.
- Read auditing, retention, exports, authentication mechanics, snapshot
  isolation, beneficiary alias exposure and the supporting index are explicitly
  out of scope for this increment.
- Identity: the current mechanism reads `X-Actor-Id` directly with no
  authentication filter; this ADR reuses it and introduces no new
  authentication semantics.
- At the time of acceptance, implementation was not authorized by this ADR; it
  required separate explicit human approval, which was later granted (see
  "Post-implementation note").
