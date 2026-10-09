# ADR-18: Transfer Atomicity and Lock Ordering

## Context

Deposit (ADR-15 plus `docs/deposit-design.md`) proves single-row monotonic
credits with the Hybrid physical representation. Withdrawal (ADR-16 plus
`docs/withdrawal-design.md`) proves single-row funds contention with
check-after-lock inside one local transaction.

The system still cannot move value between accounts. ADR-05 defines Transfer as
one operation plus two linked movements with conceptual atomicity but leaves
technical atomicity, locks, isolation, and concurrency enforcement explicitly
OPEN. ADR-07 requires validation-plus-effect in one consistency unit but
selects no physical mechanism. ADR-02 leaves available funds as a validation
concept with physical representation OPEN; ADR-15/ADR-16 close it for one row
(stored balance plus row write lock). The two-row case is undecided: two
balances must move together, opposite transfers must not deadlock, and a
failure between debit and credit must never leave a partial result.

This ADR fixes the transfer atomicity and lock-ordering decision within those
bounds, without designing beneficiaries, lifecycle transitions, ledger,
authentication, observability, PostgreSQL validation work, or distributed
machinery. The approved T0 scope adds three human-approved decisions: destination
`BLOCKED`/`CLOSED` rejected (transfer-scoped), authorization on the source
holder, UUID-ordered locks; plus exact-balance allowed and an explicit
between-legs failure proof requirement.

## Decision

Adopt single-currency transfer with two-row atomicity and deterministic lock
ordering as the third Financial Operation, under the same Hybrid physical
representation decided in ADR-15:

```text
Financial Operation (TRANSFER)
        |
Immutable Movement (DEBIT on source) + Immutable Movement (CREDIT on destination)
        |
Source Balance State + Destination Balance State
```

- Transfer is strictly two-account single-currency: one Financial Operation of
  transfer type plus exactly two immutable movements sharing the same
  `operation_id` (one `DEBIT` on the source, one `CREDIT` on the destination).
  No second operation, no single-sided confirmation, no
  `DEBITED`/`CREDITED`/`PARTIALLY_COMPLETED` states.
- Two-account representation, Option A — DECIDED in T0.2 approval, no schema
  migration: the existing `financial_operations` schema is kept unchanged.
  `FinancialOperation.account_id` identifies the SOURCE account. The `DEBIT`
  movement references the source; the `CREDIT` movement references the
  destination; both share the single `TRANSFER` operation's `operation_id`.
  The destination is reconstructed from the linked `CREDIT` movement.
  Advantage: existing representation preserved, MVP scope reduced. Trade-off:
  obtaining the destination requires querying linked movements. Confusing
  origin with destination is impossible by construction
  (`DEBIT.account_id == operation.account_id == source`,
  `CREDIT.account_id == destination`). Recovery and authorization use the
  source account and its server-resolved holder; being solely the destination
  holder grants no access.
- Overdraft is prohibited in MVP. A transfer MUST confirm only when the source
  stored balance covers the requested amount in full at confirmation time.
- Exact-balance transfer is allowed: moving the full available source balance
  leaves source at zero and confirms (approved T0 precision, mirrors ADR-16).
- Source and destination MUST be distinct. Same-account transfer is a
  deterministic business rejection.
- Both legs MUST be single-currency MVP: operation currency MUST equal source
  currency and destination currency. No FX, no conversion, amounts in minor
  units (`long`, `> 0`).
- Only `ACTIVE -> ACTIVE` transfers confirm in MVP scope (approved T0
  decisions, transfer-scoped): `BLOCKED`/`CLOSED` on either side is a
  deterministic business rejection with no movement and no balance change.
  ADR-03 is NOT modified; any future general `BLOCKED`-credit policy requires
  its own approval.
- The definitive existence, operability, currency, and sufficient-funds checks
  MUST occur under row write locks, inside one single outer local write
  transaction (`writeTx`) that applies both effects. Pre-lock reads MUST NOT
  decide the financial outcome; they are at most a fast path.
- Locks MUST be acquired in deterministic UUID ascending order (approved T0
  decision): let `first = min(sourceId, destinationId)` and
  `second = max(sourceId, destinationId)` by UUID natural ordering and invoke
  the Accounts-owned contract belonging to `first` first, then the one
  belonging to `second` — never blindly source-then-destination. This works
  without new contracts because each of `applyDebit` / `applyCredit`
  (`@Transactional`, `REQUIRED`) locks exactly its row synchronously via
  `findByIdForUpdate` at call time, so call order IS lock order; both locks
  are then held until outer commit. The confirmation decision is taken only
  after BOTH contract results are known. No `accounts.internal` access from
  `financialops.transfer` and no dependency on `deposit` / `withdrawal`.
  An optional additive `lockBothInUuidOrder` `accounts.api` helper stays a
  human-approval proposal, NOT decided here; no impediment requiring an
  incompatible change was found.
- Stored balances remain the authoritative observable state for normal reads
  and funds checks (ADR-15). Movement history remains the authoritative
  evidence of effects. Reconciliation per account
  (`balance == sum(CONFIRMED credits) - sum(CONFIRMED debits)`) is diagnostic,
  not the read path.
- Financial Operations owns transfer operations and both movements (validity,
  lifecycle, idempotency, movement creation, confirmation). It MUST NOT
  directly mutate the Accounts persistence model nor access
  `AccountRepository`, the `Account` entity, or balance internals. Both legs
  reuse the dedicated Accounts-owned contracts: the debit contract
  (with funds check) for the source leg and the credit contract for the
  destination leg.
- Transfer idempotency lives in its own namespace
  (`transfer_operation_idempotency`), separate from deposit, withdrawal,
  account-creation, and customer-creation namespaces. Canonical hash input
  (exact order, `|` separator, no whitespace):
  `source-uuid-lowercase|destination-uuid-lowercase|amount-base10|currency-upper`,
  UTF-8 bytes, SHA-256 hex lowercase (64 chars); amount is
  `Long.toString(amountMinorUnits)`. Same intent plus same identity MUST
  NOT create a second effect. Same identity plus incompatible payload is a
  conflict. Each new identity is a distinct monetary intent. Recovery requires
  `Idempotency-Key-Hash` plus source-holder (or `BANK_EMPLOYEE`) authorization;
  the destination holder alone MUST NOT recover nor learn the result, and
  denied responses MUST NOT leak foreign operation details.
- `UNKNOWN` is caller-side uncertainty, never a persisted state. `FAILED` is
  used only for determined technical failures with no financial effect (for
  example arithmetic overflow). `REJECTED` is the deterministic business
  outcome with no movement and no balance change on either side.
- Only the source holder authorizes (approved T0 decision): self
  (`actor == source holder`) or `BANK_EMPLOYEE` under the current stub. Both
  holders are server-resolved; the request carries no holder identity.

## Atomicity

A confirmed transfer MUST atomically coordinate, within one single outer local
write transaction (`writeTx`): operation state, two movement creations, two
balance effects (via the `REQUIRED`-joining `applyDebit` / `applyCredit`
contracts, never committing independently), idempotency state, and audit event.
No distributed transaction. `flush` is NOT a commit: it only surfaces integrity
errors early inside the same `writeTx`; no unnecessary partial flushes are
introduced between legs, with one explicit `flush` before the confirmation
decision mirroring Deposit/Withdrawal.

A failure before commit — including a failure between the debit application
and the credit application — leaves no movement and no balance change on
either side; a committed `CONFIRMED` transfer always carries its full effects.
A `REJECTED` transfer persists its operation, idempotency, and audit evidence
with no movement and no balance change. There is no valid partially applied
confirmed result: no source debit without destination credit, no balance
change without its movement, no `CONFIRMED` operation/idempotency/audit
without both effects, and no representation confusing origin with destination
(`operation.account_id == source`, exactly one `DEBIT` on the source and one
`CREDIT` on the destination sharing the single `operation_id`; no
`destination_account_id` column in MVP).

Validation strategy (design requirement): H2 failure-injection tests that throw
between the two legs MUST assert zero operation, movement, idempotency, and
`CONFIRMED` audit effects with both balances intact
(mirroring `DepositAtomicityTest` / `WithdrawalAtomicityTest`); the same
rollback proof MUST run against real PostgreSQL (`postgres:16-alpine`) in the
`*Postgres*` gate, because PostgreSQL aborts the whole transaction on
constraint violation and has distinct lock/visibility semantics.

## Consequences

- What becomes easier or more possible:
  - first real multi-row financial invariant (no double spend across accounts,
    no negative source, no partial transfer, no deadlock on opposite pairs);
  - deterministic concurrent-transfer behavior through a global lock order;
  - immutable two-movement evidence and full reconstruction from movements;
  - per-leg reconciliation extended to transfers;
  - a proven transfer base that a future beneficiary transfer can reuse.
- What becomes harder or more difficult:
  - every confirming transfer path MUST preserve two-row write-path discipline
    and UUID-ordered check-after-lock ordering;
  - opposite-transfer and funds-competition concurrency tests become
    mandatory, on H2 and on real PostgreSQL;
  - between-legs rollback tests become mandatory, on H2 and on real
    PostgreSQL;
  - insufficient-funds and inoperable-either-side semantics MUST stay
    deterministic under serialization;
  - persistence tests MUST prove each confirmed transfer carries exactly the
    linked `DEBIT`-source plus `CREDIT`-destination pair with no
    origin/destination confusion.
- Trade-offs:
  - two locks plus ordering discipline now versus single-row simplicity that
    cannot express value movement;
  - Option A (destination via linked `CREDIT` movement, no schema migration)
    now versus a dedicated `destination_account_id` column with direct reads
    at the cost of a migration;
  - a separate transfer namespace and transfer-scoped operability verdict now
    versus a generic abstraction that would hide the funds and destination
    invariants;
  - strict no-overdraft plus `ACTIVE -> ACTIVE` only now versus product
    flexibility deferred until a real need justifies it.

## Alternatives Considered

- Two independent operations (debit op + credit op) -> Rejected: weakens
  atomicity, idempotency, and reconciliation; already rejected by ADR-05.
- Adding a `destination_account_id` column to `financial_operations` -> Rejected
  for Transfer MVP (T0.2 Option A): the destination is reconstructed from the
  linked `CREDIT` movement with the existing schema; a dedicated column MAY be
  reconsidered later if query/traceability needs justify it.
- Source-then-destination lock order -> Rejected: opposite transfers can hold
  one lock and wait for the other in opposite order; UUID order gives the same
  cost with a global order.
- Validate funds before acquiring both locks (optimistic pre-check as
  decision) -> Rejected as decision mechanism: either balance can go stale
  before effects apply. Pre-lock reads MAY exist as a fast path.
- Optimistic check without serialization -> Rejected as primary mechanism: it
  leaves the two-row validation-plus-effect unit without a proven
  serialization point and contradicts the ADR-15/ADR-16 lock strategy.
- Share a deposit/withdrawal idempotency namespace -> Rejected: conflates
  distinct monetary intents under one key space; same discipline, separate
  namespace.
- Single generic balance applicator for both legs -> Rejected: the source leg
  needs a funds check and the destination leg does not; reuse the two
  dedicated Accounts-owned contracts instead of hiding the difference.
- Allow overdraft, fees, or limits now -> Rejected for MVP: no product
  justification; deferred.
- Allow `BLOCKED` destination credit generally now -> Rejected here: kept as a
  transfer-scoped rejection; general policy stays OPEN per ADR-03.
- Same-account transfer as a no-op success -> Rejected: it would record
  movements and audit for no economic effect; deterministic rejection is
  safer and explicit.
- Cross-currency/FX in transfer -> Rejected for MVP: single-currency only per
  ADR-05 MVP.
- Introduce formal ledger / double-entry now -> Rejected for MVP per ADR-02:
  movements stay the effect record and future ledger base.
- Sagas, events, queues, distributed transactions, microservices -> Rejected
  per ADR-01/ADR-14/ADR-17: one local transaction suffices; no distribution
  without evidence.

Explicit non-decisions: exact table and column shapes, index selection,
`CHECK` selection, isolation levels, locking statements beyond the UUID-ordered
contract-invocation discipline above, ORM mapping, exact
HTTP paths beyond the `POST /api/v1/transfers` plus outcome-query direction,
authentication mechanics, retention/cleanup policy, and recovery job
implementation. The only foreseen shared-model change for implementation is
adding `TRANSFER` to `FinancialOperationType` in `financialops.core`; `core`
MUST NOT depend on the `transfer` package. They belong to design and
implementation within this decision's bounds. Beneficiary model, lifecycle transitions, PostgreSQL validation work
itself, observability tooling, and any distributed concern are explicitly out
of this ADR.

## Relationship to Existing ADRs

- ADR-01: preserves Modular Monolith boundaries (FinOps coordinates through
  Accounts-owned contracts only, local transactions, no cross-module
  persistence access). No boundary change.
- ADR-02: first two-movement application of the Hybrid model (1 operation +
  2 movements, compensation-only correction). No model change.
- ADR-03: applies existing operability and immutable single currency; records
  a transfer-scoped `ACTIVE -> ACTIVE` verdict without modifying the general
  `BLOCKED`-credit OPEN. No lifecycle change.
- ADR-04: applies PENDING/CONFIRMED/REJECTED/FAILED with explicit recovery;
  `UNKNOWN` stays caller knowledge; `FAILED` stays determined-no-effect. No
  lifecycle change.
- ADR-05: closes its OPEN technical atomicity and lock enforcement for the
  single-currency case (one local transaction plus UUID-ordered locks); source,
  destination, currency, funds, idempotency, and compensation model follow
  ADR-05. No model change; implementation bound filled.
- ADR-06: same logical-identity determinism applied to a separate transfer
  namespace with `source|destination|amount|currency` hash and first-class
  outcome query. No idempotency-principle change.
- ADR-07: validation-plus-effect in one consistency unit applied to two rows;
  conflicts resolve deterministically (`REJECTED` for business, `FAILED` only
  for determined-no-effect technical failure). Fills the two-row mechanism
  that ADR-07 left OPEN. No concurrency-principle change.
- ADR-09: keeps stub authorization, scoped to the source holder; no provider
  change.
- ADR-10: versioned REST direction, queryable results, safe errors preserved.
  No API-principle change.
- ADR-11: transverse audit with immutable confirmed evidence
  (`TRANSFER_CONFIRMED`/`REJECTED`/`CONFLICT`); history/audit/logs separation
  preserved. No audit-principle change.
- ADR-13: risk-and-invariant testing extended to two-row atomicity, opposite
  transfers, funds competition, between-legs rollback, and per-leg
  reconciliation, on H2 plus PostgreSQL. No strategy change.
- ADR-15: reuses stored-balance-plus-immutable-movements, single-transaction
  confirmation, and reconciliation-as-diagnostic on two rows. No
  representation change.
- ADR-16: reuses debit-side funds discipline (post-lock check, exact-balance
  allowed, separate namespace, dedicated contracts) for the source leg and
  extends it with a destination leg plus lock ordering. No withdrawal change.

No existing ADR is modified by this decision.

## Status

Accepted - approved by human review after T0-T0.3 design and coherence audit.

## Notes

- Compatible with ADR-01 through ADR-17 at creation time. This ADR does not
  design beneficiaries, lifecycle transitions, ledger evolution,
  authentication, observability tooling, PostgreSQL validation work itself, or
  any distributed concern.
- Recorded contradictions (not fixed here): ADR-14 `No ADR-15 is created now`
  is stale; `README.md` setup-only description is stale versus
  Deposit/Withdrawal; `MovementDirection`/`FinancialOperationStatus` javadocs
  are stale; ADR-01 `Proposed` versus ADR-17 `Accepted` state inversion.
- Language: MUST for decided architectural rules; SHOULD for recommendations;
  MAY for future possibilities.
