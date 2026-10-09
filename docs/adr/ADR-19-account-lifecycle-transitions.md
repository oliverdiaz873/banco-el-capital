# ADR-19: Account Lifecycle Transitions

## Context

Accounts are born `ACTIVE` and today can never leave that state: `BLOCKED`
and `CLOSED` exist in `AccountStatus` but no code path reaches them and no
test covers them. Deposit (`docs/deposit-design.md`), Withdrawal
(`docs/withdrawal-design.md`), and Transfer (`docs/transfer-design.md`, ADR-18)
all evaluate operability through the single shared predicate
`Account.isOperable() == ACTIVE` under the account-row write lock, with
transfer-scoped verdicts that reject `BLOCKED`/`CLOSED` on either side.

ADR-03 defines the lifecycle model (`ACTIVE <-> BLOCKED -> CLOSED`, plus
`ACTIVE -> CLOSED`; `CLOSED` terminal; `BLOCKED`-credit OPEN) but no
transition mechanism. The states are therefore ungovernable: risk cannot be
frozen and accounts cannot be closed. This ADR makes the lifecycle executable
within those bounds, without designing beneficiaries, PENDING/async creation,
product catalogs, ledgers, authentication, observability, PostgreSQL
validation work, or distributed machinery.

## Decision

Adopt explicit Accounts-owned lifecycle transitions as the fourth Banco El
Capital capability:

```text
ACTIVE <-> BLOCKED -> CLOSED
ACTIVE -> CLOSED
```

- `block`: `ACTIVE -> BLOCKED` only. Repeating on `BLOCKED` is a
  deterministic no-op success; on `CLOSED` it is rejected.
- `unblock`: `BLOCKED -> ACTIVE` only. Repeating on `ACTIVE` is a
  deterministic no-op success; on `CLOSED` it is rejected.
- `close`: `ACTIVE -> CLOSED` or `BLOCKED -> CLOSED`, with zero balance
  required (P2 decided; non-zero balance rejects deterministically). On
  `CLOSED` it is always rejected (terminal state is never re-confirmable).
- `PENDING` is not introduced; transitions are synchronous.
- A transition is NOT a `FinancialOperation`: no amount, no currency, no
  movement, no funds check. It carries its own `CONFIRMED` / `REJECTED` /
  `FAILED` states with caller-side `UNKNOWN` recovery, in a dedicated
  idempotency namespace (shape deferred to implementation).
- Transitions execute inside `accounts.internal` through the existing
  `AccountRepository.findByIdForUpdate` row lock, in one local transaction
  covering the state effect, the idempotency record, and the audit event. No
  `accounts.internal` exposure, no new cross-module persistence access.
- Financial Operations needs no new contract: it keeps observing operability
  through the existing `AccountCreditService` / `AccountDebitService` checks
  under their own locks, so a concurrent money operation and transition
  serialize deterministically with no invalid final state.
- Authorization follows the current stub: only `BANK_EMPLOYEE` may block,
  unblock, or close (P3 decided; stub role, not production banking security).
  The request carries no holder identity; emergency self-block is a separate
  future capability.
- A minimal `GET` account read (`accountId, holderCustomerId, currency,
  productCode, status, balanceMinorUnits, createdAt`), authorized to
  holder-or-employee, makes the lifecycle observable; no account read exists
  today.
- The general `BLOCKED`-credit policy (P1, decided: all ordinary money
  REJECTED; special future credits need explicit contracts) and
  close-with-balance (P2, decided: close requires zero balance) are recorded
  in the lifecycle design document. Special credits into `BLOCKED` and
  settlement-at-close stay explicitly out of scope.

## Atomicity

A confirmed transition MUST atomically coordinate, within the same local
database transaction: the account state effect, the transition idempotency
state, and the audit event. No distributed transaction. A failure before
commit leaves the state untouched; a committed transition always carries its
idempotency and audit evidence. A rejected transition persists its
idempotency and rejection audit with no state change. There is no valid
partially applied transition.

Validation strategy (design requirement): H2 unit, persistence, atomicity
(failure injection with full rollback), idempotency/conflict/recovery,
concurrency (money-vs-transition both orders, same-key convergence),
API matrix, and ArchUnit regression; the same rollback and concurrency proofs
MUST run against real PostgreSQL (`postgres:16-alpine`) in the `*Postgres*`
gate, because PostgreSQL aborts the whole transaction on constraint violation
and has distinct lock semantics.

## Consequences

- What becomes easier or more possible:
  - governable risk states and terminal closure with a traceable trail;
  - a uniform operability rule across deposit, withdrawal, and transfer once
    P1 is approved;
  - deterministic money-vs-transition races through the existing lock point;
  - observable account state for holders and employees.
- What becomes harder or more difficult:
  - every money path MUST keep checking operability under lock (already the
    case; now against reachable states);
  - P1/P2/P3 were explicit human decisions gating the general policy (see
    lifecycle design document);
  - transition idempotency and audit add a second (non-financial) intent
    namespace to maintain.
- Trade-offs:
  - explicit transitions plus audit now versus an ungovernable constant
    `ACTIVE` that silently accumulates risk;
  - a minimal account read now versus lifecycle changes no one can observe.

## Alternatives Considered

- Boolean `active` flag -> Rejected per ADR-03: cannot express
  blocked/closed operability and transitions.
- Transitions as `FinancialOperation`s with movements -> Rejected: no money
  moves; it would pollute movement history and reconciliation and blur the
  audit separation.
- Generic state-machine framework -> Rejected: three states with explicit
  rules need no framework.
- `BLOCKED` auto-expiry/timers -> Rejected for MVP: no temporal requirement;
  explicit `unblock` only.
- Cascade-closing related data -> Rejected: no such relations exist;
  `CLOSED` freezes the row only.
- Sagas, events, queues, distributed transactions, microservices -> Rejected
  per ADR-01/ADR-14/ADR-17: one local row transaction suffices.

Explicit non-decisions: exact table and column shapes beyond the existing
`status` column, index selection, isolation levels, locking statements beyond
the existing row-lock discipline, ORM mapping, exact HTTP paths beyond the
`POST .../block|unblock|close` plus outcome-query direction, the `GET`
projection beyond the minimal read model, authentication mechanics,
retention/cleanup policy, and recovery job implementation. P1 (general
`BLOCKED`-credit), P2 (close-with-balance), and P3 (transition authorization)
are explicitly pending human approval. Beneficiary model, PENDING/async
creation, product catalogs, ledger evolution, observability tooling,
PostgreSQL validation work itself, and any distributed concern are explicitly
out of this ADR.

## Relationship to Existing ADRs

- ADR-01: transitions stay inside Accounts (`internal` plus feature `web`),
  local transactions, no cross-module persistence access. No boundary change.
- ADR-02: transitions create no movements and change no balances; the Hybrid
  model is untouched. No model change.
- ADR-03: makes the defined machine executable; records transfer-scoped
  verdicts; leaves `BLOCKED`-credit OPEN pending P1 and PENDING out.
  No lifecycle-model change; two policy points stay explicitly open.
- ADR-04: applies CONFIRMED/REJECTED/FAILED with explicit recovery to a
  non-financial intent; `UNKNOWN` stays caller knowledge. No lifecycle change.
- ADR-05/ADR-16/ADR-18: money paths keep their contracts and lock ordering;
  only the reachable states widen, under existing per-call operability
  checks. No transfer/withdrawal change.
- ADR-06: same logical-identity determinism in a dedicated transition
  namespace. No idempotency-principle change.
- ADR-07: validation-plus-effect in one consistency unit applied to one row;
  money-vs-transition conflicts resolve deterministically. No
  concurrency-principle change.
- ADR-09: keeps stub authorization, scoped per P3. No provider change.
- ADR-10: versioned REST direction, queryable results, safe errors preserved.
  No API-principle change.
- ADR-11: transverse audit with `ACCOUNT_BLOCKED/UNBLOCKED/CLOSED`,
  `ACCOUNT_TRANSITION_REJECTED/CONFLICT`; history/audit/logs separation
  preserved. No audit-principle change.
- ADR-13: risk-and-invariant testing extended to transitions, money races,
  and predicate coherence. No strategy change.
- ADR-15: stored balance untouched by transitions; `isOperable()` stays the
  read gate. No representation change.
- ADR-17: transitions live in `accounts` (`internal` plus feature `web`);
  no new `shared`, no ornamental splits. No packaging change.

No existing ADR is modified by this decision.

## Status

Accepted - approved by human review with P1-P3 incorporated.

## Notes

- Compatible with ADR-01 through ADR-18 at creation time. This ADR does not
  design beneficiaries, PENDING/async creation, product catalogs, ledger
  evolution, authentication, observability tooling, PostgreSQL validation work
  itself, or any distributed concern.
- Recorded decisions (human-approved, see lifecycle design document): P1 general
  `BLOCKED` ordinary-money rejection (special future credits need explicit
  contracts), P2 close requires zero balance, P3 `BANK_EMPLOYEE`-only
  transitions; P4 idempotency shape and P5 read scope are implementation
  details.
- Language: MUST for decided architectural rules; SHOULD for recommendations;
  MAY for future possibilities.
