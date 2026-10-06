# Feature Documentation: Withdrawal (debit-only Financial Operation)

## Objective

Second Financial Operation of Banco El Capital: an authorized actor debits an explicit
positive amount, in the account's own currency, from one ACTIVE account, only when
sufficient funds exist. Hybrid representation per ADR-15 and ADR-16 (immutable movements
plus stored observable balance, applied atomically), full lifecycle per ADR-04,
money-grade idempotency per ADR-06 in a dedicated withdrawal namespace.

The fundamental difference from Deposit:

> Distinct withdrawals compete for a limited financial resource: the available balance.

Deposit proves monotonic credits. Withdrawal proves the first funds-contention
invariant: concurrent debits MUST NOT double-spend the same balance and MUST never
produce a negative balance.

## Scope

In scope: single-account debit validation, sufficient-funds invariant, check-after-lock
ordering, dedicated debit contract owned by Accounts, withdrawal lifecycle and
idempotency, single-transaction atomicity, minimal audit, conceptual API direction,
invariant-based testing, and H2-transitory risk documentation.

Out of scope: transfers (one operation plus two movements), lock ordering between
accounts, beneficiaries and third-party transfers, compensation/reversal execution,
account lifecycle transitions (block/close), formal ledger/double-entry, event sourcing,
sagas/Kafka/microservices/distributed transactions, real authentication
(JWT/OAuth/Spring Security), PostgreSQL/Testcontainers validation work, and advanced
observability. If a Withdrawal decision prepares Transfer, the relationship is noted
without designing Transfer.

## Financial Invariant

```text
confirmed withdrawal <= available balance at confirmation time
```

```text
balance >= 0 always (no confirmed state may violate this)
```

Reconciliation is diagnostic only, never the read path:

```text
balance = sum(confirmed credits) - sum(confirmed debits)
```

Balance reads stay O(1) on stored state per ADR-15. Aggregation over movements is the
integrity check.

## Business Rules

A withdrawal confirms only when all hold:

- amount is a positive minor-units value (`long`, `> 0`);
- currency is explicit and equals the account currency (single-currency MVP, no FX);
- account exists (server-resolved; unknown ids reject deterministically);
- actor is authorized under the current stub (self or BANK_EMPLOYEE via `X-Actor-Id`);
- account is operable under current rules (`ACTIVE`; `BLOCKED`/`CLOSED` reject);
- sufficient funds: `available balance >= requested amount`;
- the effect can be applied atomically in one local transaction;
- the outcome is deterministically recoverable via idempotency.

Exact-balance withdrawal is allowed (`10000 - 10000 = 0 CONFIRMED`). Zero balance does
not allow any positive withdrawal. Insufficient funds is a deterministic business
rejection (`REJECTED`), not a technical failure: no movement, no balance change.

## Concurrency

Serialization point: the account-row write lock (`SELECT ... FOR UPDATE` semantics
through the Accounts-owned contract). Parallel debits on one account sequence instead
of producing invalid states.

Ordering discipline:

1. Optional fast-path reads (existence, idempotency pre-check) do not decide.
2. Acquire the write lock on the account row.
3. Post-lock idempotency re-check: if a concurrent same-key winner committed while
   waiting, replay it instead of duplicating.
4. Definitive funds check on the locked balance.
5. Apply the debit and confirm all effects in the same transaction.

Examples (all in minor units):

- `10000` with concurrent `7000 + 7000`: exactly one confirms, the other rejects for
  insufficient funds; final `3000`. Never negative, never two confirmed.
- `10000` with concurrent `4000 + 6000`: serialization order permitting, both confirm;
  final `0`.
- `10000` with concurrent `5000 + 5000 + 5000`: exactly two confirm, one rejects;
  final `0`.

No lock ordering between accounts is introduced: Withdrawal locks at most one row.

## Idempotency

Same discipline as Deposit, separate namespace:

- `Idempotency-Key` required; canonical request hash over
  `account|amount|currency` (SHA-256 style, mirroring deposits).
- Same key plus same payload on a confirmed withdrawal replays without new effects
  and without new audit of the original effect.
- Same key plus same payload on a rejected withdrawal returns the same deterministic
  rejection without new effects.
- Same key plus different payload is a conflict; no money moves and idempotency state
  is untouched.
- Different keys with identical payload are separate withdrawal intents; each
  competes for funds at its own serialization point (the second may reject for
  insufficiency even if the first confirmed).
- Retry after timeout/disconnect reuses the original key or the outcome query; a new
  key for recovery is forbidden because it would create a second debit.
- `UNKNOWN` is caller-side uncertainty (committed-or-not plus lost response), never a
  persisted state.

## Financial Operation

Withdrawal uses the ADR-04 lifecycle without new states:

- `CONFIRMED`: committed operation with debit movement, updated balance, and audit.
- `REJECTED`: deterministic business refusal (unknown account, inoperable state,
  currency mismatch, invalid amount, insufficient funds, unauthorized actor); no
  movement, no balance change, rejection audit.
- `FAILED`: used only when the system determines a technical failure occurred and the
  operation has no financial effect (for example arithmetic overflow); no movement,
  no balance change, no fabricated confirmation. Never assigned merely because the
  client missed the response.
- `UNKNOWN`: caller-side uncertainty. Neither `FAILED` nor `CONFIRMED` may be
  inferred. Recovery is always the original identity.
- `PENDING` remains conceptual/transitory, not a persisted stable state in this
  increment, consistent with the Deposit implementation.

Compatibility with ADR-04 stands: no formal `UNKNOWN` in MVP; `FAILED` here is the
determined-no-effect subset; uncertain server-side states resolve through the
idempotency query.

## Movement

- Direction `DEBIT` (already declared in `MovementDirection`, used here for the first
  time), amount in minor units, explicit currency, owning operation, account, and
  timestamp.
- Created only when its operation confirms; immutable afterwards: never updated,
  never deleted. Correction happens through a future compensatory operation, not by
  editing history (out of scope here).
- One confirmed withdrawal carries exactly one `DEBIT` movement. No multi-movement
  operations in this increment.
- Mixed-history reconciliation (`credits - debits`) becomes the mandatory diagnostic
  alongside every withdrawal feature.

## Atomicity

A confirmed withdrawal MUST atomically coordinate, within the same local database
transaction: operation state, debit movement creation, balance effect, idempotency
state, and audit event. No distributed transaction.

A rejected withdrawal persists operation, idempotency, and rejection audit with no
movement and no balance change.

Technical-failure handling: failure before commit leaves no movement and no balance
change; a committed `CONFIRMED` operation always carries its full effects. Failure
while persisting idempotency or audit rolls back the whole confirmation. There is no
valid partially applied confirmed result:

- no balance change without movement;
- no confirmed movement without corresponding balance;
- no `CONFIRMED` operation without effect;
- no `CONFIRMED` idempotency without effect;
- no `CONFIRMED` audit without effect.

## Account Ownership

Accounts owns: existence, lifecycle/operability, currency, stored balance value, the
row lock, and the dedicated debit contract that re-verifies all preconditions
(including sufficient funds) before mutating.

Financial Operations coordinates: authorization input, validation orchestration,
operation lifecycle, movement creation, idempotency, atomic confirmation, and outcome
query. It MUST NOT access `AccountRepository`, the `Account` entity, or balance
internals directly.

The debit contract stays separate from `AccountCreditService` because the invariants
differ: credit never checks funds, debit always does. A single generic
credit/debit applicator is rejected as a premature abstraction.

## Authorization

Current stub is retained, no new auth design:

- authenticated actor required;
- self (actor equals holder) or `BANK_EMPLOYEE` role;
- holder server-resolved from the account; the request carries no holder identity;
- `X-Actor-Id` header style preserved.

No JWT/OAuth/OIDC, Spring Security, sessions/tokens, MFA, or full RBAC in this
increment (ADR-09).

## Audit

Minimum events, mirroring deposits:

- `WITHDRAWAL_CONFIRMED`;
- `WITHDRAWAL_REJECTED` (including insufficient funds, without exposing the balance);
- `WITHDRAWAL_CONFLICT`.

Replay creates no new effect audit. Conflict evidence is recorded in its own write
unit without mutating idempotency state. No full balances, credentials, tokens, PII,
or internals are logged unnecessarily. History/audit/logs separation per ADR-11 is
preserved.

## API Direction

Conceptual direction, consistent with deposits (`POST /api/v1/deposits` plus outcome
query with `Idempotency-Key-Hash`):

- creation resource for withdrawals with `Idempotency-Key` and `X-Actor-Id`;
- queryable outcome by identity for timeout/disconnect recovery, authorized against
  the server-resolved holder and guarded by request hash;
- status mapping by symmetry: `201` new, `200` replay, `400` validation,
  `401` unauthenticated, `403` forbidden, `409` conflict, `422` deterministic business
  rejection (including insufficient funds), `500` technical/unknown with same-key
  recovery message;
- safe errors that do not reveal account existence, balances, personal data, or
  internals.

Exact resource and query-path names follow deposit symmetry unless review requires
otherwise; no definitive contract beyond this direction is fixed here.

## Testing Strategy

Invariant-based, mirroring the Deposit layers plus funds contention:

- Unit: amount validation, sufficient/insufficient, exact balance, zero balance,
  currency mismatch, account not found, non-operable, arithmetic boundaries.
- Persistence: debit operation, `DEBIT` movement, balance persistence, dedicated
  idempotency persistence.
- Atomicity: failure injection with full rollback (no partial operation/movement/
  balance/idempotency/audit).
- Idempotency: same-key/same-payload (confirmed and rejected), same-key/different-
  payload conflict, different keys as separate debits, retry and `UNKNOWN` recovery.
- Concurrency: same-key convergence to one effect; different keys with sufficient
  funds all confirm; competing withdrawals exceeding balance confirm only those
  preserving the invariant (including the three numeric scenarios above and larger
  parallel batches); proof that no negative balance ever appears.
- Reconciliation: mixed credit/debit histories satisfy
  `balance = credits - debits`; rejections leave balance and movements untouched.
- API: full status matrix and safe-error behavior, including authorized versus
  unauthorized outcome queries.
- Architecture: extend ArchUnit only if the new debit contract requires new rules;
  no rule without an invariant reason. Regression on all existing suites.

## PostgreSQL Validation

H2 remains transitory. All concurrency/atomicity proofs in this design target H2
first with PostgreSQL-compatible semantics (row locks, explicit flush, fresh-read
winner reload, post-lock re-check), but real PostgreSQL/Testcontainers validation
stays a follow-up when Docker is available. No Docker/Testcontainers is introduced
in this increment.

Known PG-sensitive areas carried over from Deposit and extended to the funds check:
locking behavior under contention, isolation and visibility of the winner record,
constraint-violation timing (commit versus flush), whole-transaction abort semantics,
and rollback completeness under concurrent load.

## Future Evolution

Withdrawal prepares, without designing:

```text
Withdrawal (single debit, funds) -> Transfer (debit + credit atomically) -> Beneficiary Transfer
```

The debit contract, check-after-lock ordering, separate idempotency discipline, and
mixed reconciliation established here become the reusable source-leg foundation for a
future single-operation/two-movement transfer. No transfer semantics, lock ordering,
beneficiary resolution, ledger, or distribution is decided here.
