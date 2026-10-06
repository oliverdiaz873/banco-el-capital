# ADR-16: Single-Account Withdrawal with Sufficient Funds

## Context

Deposit (ADR-15 plus `docs/deposit-design.md`) provides the first Financial Operation of
Banco El Capital as a credit-only effect: immutable movements plus stored observable
balance applied atomically, full lifecycle per ADR-04, money-grade idempotency per ADR-06,
and concurrency serialization through the account-row write lock.

The system still has no debit financial effect. Credits are monotonically increasing, so
concurrent credits can only risk lost updates, never an invalid funds state. The next
central risk is guaranteeing that concurrent operations cannot spend the same balance
twice: withdrawals compete for a limited financial resource, the available balance.

ADR-02 selected a Hybrid Financial Model with Movement as confirmed immutable effect and
Balance as queryable state consistent with confirmed effects, leaving available funds as
a validation concept with physical representation OPEN. ADR-03 defines Account lifecycle
and operability (ACTIVE operable, BLOCKED/CLOSED not operable for debits) with currency
immutable and single-currency MVP. ADR-04 defines the operation lifecycle with explicit
recovery and no formal UNKNOWN state in MVP. This ADR fixes the debit-side decision for
a single account within those bounds, without designing transfers, beneficiaries,
lifecycle transitions, ledger, authentication, observability, or distributed machinery.

## Decision

Adopt single-account withdrawal with sufficient funds as the second Financial Operation,
under the same Hybrid physical representation decided in ADR-15:

```text
Financial Operation (WITHDRAWAL)
        |
Immutable Movement (DEBIT)
        |
Account Balance State
```

- Withdrawal is strictly single-account: one Financial Operation of withdrawal type plus
  exactly one immutable `DEBIT` Movement on the same account. No second account, no
  second movement, no lock ordering between accounts.
- Overdraft is prohibited in MVP. A withdrawal MUST confirm only when the available
  stored balance covers the requested amount in full.
- Exact-balance withdrawal is allowed: withdrawing the full available balance leaves
  balance zero and confirms.
- Sufficient funds is a financial invariant, not a best-effort check: a confirmed
  withdrawal implies `confirmed withdrawal <= available balance at confirmation time`
  and the resulting `balance >= 0`.
- The definitive funds check MUST occur after acquiring the account-row write lock
  (`SELECT ... FOR UPDATE` semantics through the Accounts-owned contract), inside the
  same local transaction that applies the debit. A pre-lock read MUST NOT decide the
  financial outcome; it is at most a fast path.
- Stored balance remains the authoritative observable state for normal reads and funds
  checks (ADR-15). Movement history remains the authoritative evidence of effects.
- Reconciliation `balance = sum(CONFIRMED credits) - sum(CONFIRMED debits)` is a
  diagnostic and integrity mechanism, not the normal read path.
- Financial Operations owns withdrawal operations and debit movements (validity,
  lifecycle, idempotency, movement creation, confirmation). It MUST NOT directly
  mutate the Accounts persistence model nor access `AccountRepository`, the `Account`
  entity, or balance internals.
- Accounts owns the stored balance state, operability, currency, and the row lock. A
  dedicated Accounts-owned debit contract, separate from the existing credit contract,
  re-verifies existence, operability, currency, amount validity, and sufficient funds
  before mutating. Credit and debit contracts stay separate because their invariants
  differ (credit never checks funds; debit always does).
- Withdrawal idempotency lives in its own namespace, separate from deposit
  idempotency. Same logical withdrawal intent plus same identity MUST NOT create a
  second effect. Same identity plus incompatible payload is a conflict. Each new
  identity is a distinct monetary intent; different identities with identical payload
  are separate withdrawals that compete for funds at their own serialization point.
- UNKNOWN is caller-side uncertainty, never a persisted state. A client that loses the
  response retries with the same identity or queries the stored outcome. FAILED is
  used only for determined technical failures with no financial effect (for example
  arithmetic overflow). REJECTED is the deterministic business outcome (unknown
  account, inoperable state, currency mismatch, invalid amount, insufficient funds,
  unauthorized actor) with no movement and no balance change.
- Only `ACTIVE` accounts are withdrawable under current operability rules, consistent
  with the deposit-scoped verdict documented in `docs/deposit-design.md`. No lifecycle
  transition is introduced here.

## Atomicity

A confirmed withdrawal MUST atomically coordinate, within the same local database
transaction: operation state, debit movement creation, balance effect, idempotency
state, and audit event. No distributed transaction. A failure before commit leaves no
movement and no balance change; a committed CONFIRMED withdrawal always carries its
full effects. A REJECTED withdrawal persists its operation, idempotency, and audit
evidence with no movement and no balance change. There is no valid partially applied
confirmed result.

## Consequences

- What becomes easier or more possible:
  - first real funds-contention invariant (no double spend, no negative balance);
  - deterministic concurrent-debit behavior through an existing lock point;
  - immutable debit evidence and full reconstruction from movements;
  - reconciliation capability extended to debits;
  - a proven debit side that a future transfer can reuse for its source leg;
  - clear modular ownership (effects versus state) preserved on the debit path.
- What becomes harder or more difficult:
  - every confirming debit path MUST preserve write-path discipline and
    check-after-lock ordering;
  - reconciliation tests become mandatory for mixed credit/debit histories;
  - competing-withdrawal concurrency tests become mandatory, not optional;
  - insufficient-funds semantics MUST stay deterministic under serialization.
- Trade-offs:
  - a dedicated debit contract plus a separate idempotency namespace now versus a
    smaller generic abstraction that would hide the funds invariant;
  - strict no-overdraft rule now versus product flexibility (limits, overdraft)
    deferred until a real product need justifies it.

## Alternatives Considered

- Allow overdraft (with limit or overdraft product) -> Rejected for MVP: no product
  justification exists; it would weaken the first funds invariant and complicate
  concurrency reasoning. Deferred until a real product rule requires it.
- Validate funds before acquiring the row lock (optimistic pre-check as decision) ->
  Rejected as decision mechanism: the balance can become stale before the debit
  applies. A pre-lock read MAY exist as a fast path, but the definitive check MUST be
  post-lock.
- Optimistic check without serialization (no write lock, retry on conflict) ->
  Rejected as primary mechanism: it leaves the validation-plus-effect unit without a
  proven serialization point and contradicts the ADR-15 lock strategy already adopted
  for credits. The account-row write lock stays the serialization point.
- Share the deposit idempotency table/namespace -> Rejected: it would conflate
  distinct monetary intents (credit versus debit) under one key space and complicate
  replay/conflict semantics. Same discipline, separate namespace.
- Single generic credit/debit application contract -> Rejected: credit and debit have
  different invariants (funds check only on debit). A generic contract hides that
  difference and creates a premature abstraction over the balance mutation point.
- Introduce formal ledger / double-entry now -> Rejected for MVP: excessive for the
  current need and already deferred by ADR-02. Movements remain the effect record and
  the future ledger base, without imposing double-entry now.

Explicit non-decisions: exact table and column shapes, index selection, isolation
levels, locking statements, ORM mapping, API shapes, authentication mechanics, and
recovery job implementation. They belong to design and implementation within this
decision's bounds. Transfer representation, beneficiary model, lifecycle transitions,
PostgreSQL/Testcontainers validation, observability tooling, and any distributed
machinery are explicitly out of this ADR.

## Relationship to Existing ADRs

- ADR-01: preserves Modular Monolith boundaries (FinOps coordinates through
  Accounts-owned contracts only, local transactions, no cross-module persistence
  access). No boundary change.
- ADR-02: first debit application of the Hybrid model (1 operation + 1 movement,
  compensation-only correction). No model change.
- ADR-03: applies existing operability (only ACTIVE withdrawable), immutable
  currency, single-currency MVP. No lifecycle change; BLOCKED-credit policy stays
  deposit-scoped.
- ADR-04: applies PENDING/CONFIRMED/REJECTED/FAILED with explicit recovery; UNKNOWN
  stays caller knowledge, not a persisted state. No lifecycle change.
- ADR-06: same logical identity determinism applied to a separate withdrawal
  namespace. No idempotency-principle change.
- ADR-07: validation-plus-effect in one consistency unit applied to funds; conflicts
  resolve deterministically (insufficient as REJECTED, technical uncertainty per
  ADR-04). No concurrency-principle change.
- ADR-09: keeps stub authorization (self or BANK_EMPLOYEE); no provider change.
- ADR-10: versioned REST direction, queryable results, safe errors preserved. No
  API-principle change.
- ADR-11: transverse audit with immutable confirmed evidence; history/audit/logs
  separation preserved. No audit-principle change.
- ADR-13: risk-and-invariant testing extended to funds, competing debits, and mixed
  reconciliation. No strategy change.
- ADR-15: reuses stored-balance-plus-immutable-movements, single-transaction
  confirmation, and reconciliation-as-diagnostic on the debit path. Fills the debit
  side that ADR-15 anticipated; no representation change.

No existing ADR is modified by this decision.

## Status

Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes

- Compatible with ADR-01 through ADR-15 at creation time. This ADR does not design
  transfers (1 operation + 2 movements), beneficiaries, lifecycle transitions, ledger
  evolution, authentication, observability tooling, PostgreSQL validation, or any
  distributed concern.
- Language: MUST for decided architectural rules; SHOULD for recommendations; MAY for
  future possibilities.
