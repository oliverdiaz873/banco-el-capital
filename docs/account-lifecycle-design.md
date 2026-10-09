# Feature Documentation: Account Lifecycle (block / unblock / close)

## Objective

Fourth capability of Banco El Capital: explicit, traceable account-lifecycle
transitions owned by Accounts. Accounts are born `ACTIVE` and today can never
leave that state: `BLOCKED` and `CLOSED` exist in `AccountStatus` but no code
path reaches them and no test covers them. This design makes the ADR-03
lifecycle executable — `ACTIVE <-> BLOCKED -> CLOSED` (plus `ACTIVE -> CLOSED`)
— so that money operations (deposit, withdrawal, transfer) evaluate operability
against a real, governable state instead of a constant.

The fundamental difference from financial operations:

> A lifecycle transition moves no money, creates no movement, and needs no
> funds check. It changes what future money is allowed to do.

Deposit proves credits, Withdrawal proves debits, Transfer proves two-row
atomicity. Lifecycle proves state governance: freezing risk (`BLOCKED`),
terminal closure (`CLOSED`), and a uniform operability rule across all three
money paths.

## Scope

In scope: explicit transitions with authorization, per-transition idempotency,
single-row atomicity under the existing account-row write lock, minimal audit,
conceptual API direction (`POST` transitions plus a minimal `GET` account read),
invariant-based testing, and H2-transitory risk documentation.

Out of scope: beneficiaries, PENDING/async creation, multi-holder roles,
product catalog rules, limits/fees/overdraft products, compensation of closed
accounts with balance (beyond freezing semantics), formal ledger, event
sourcing, sagas, Kafka/Redis/microservices/distributed transactions, real
authentication (JWT/OAuth/Spring Security), PostgreSQL/Testcontainers
validation work beyond the follow-up discipline, and advanced observability.

## Actors

Current stub is retained, no new auth design (ADR-09):

- transitions require an authenticated actor;
- DECIDED (P3): only `BANK_EMPLOYEE` may block, unblock, or close; the holder
  — even of its own account — does not self-transition in MVP (stub role, not
  production banking security; emergency self-block is a separate future
  capability);
- the holder of the target account is server-resolved; the request carries no
  holder identity;
- `X-Actor-Id` header style preserved.

## States and Transitions

Decided state machine (ADR-03 bounds):

```text
ACTIVE <-> BLOCKED -> CLOSED
ACTIVE -> CLOSED
```

- `block`: `ACTIVE -> BLOCKED` only; repeating on `BLOCKED` is a deterministic
  no-op success (see Idempotency); on `CLOSED` it is rejected.
- `unblock`: `BLOCKED -> ACTIVE` only; on `ACTIVE` it is a deterministic no-op
  success; on `CLOSED` it is rejected (terminal means terminal).
- `close`: `ACTIVE -> CLOSED` or `BLOCKED -> CLOSED`, with zero balance
  required (P2, decided: non-zero balance rejects deterministically, so funds
  always leave through explicit, auditable financial operations first; a
  future settlement-at-close phase is out of scope); on `CLOSED` it is
  rejected (already terminal, not an error to hide: callers get a deterministic
  rejection they can act on).

`PENDING` is not introduced (synchronous transitions, ADR-03).

## Preconditions and Business Rules

A transition confirms only when all hold:

- the account exists (server-resolved; unknown ids reject deterministically);
- the actor is authorized under the transition rule (P3);
- the transition is legal from the current state (see machine above);
- for `close`: the stored balance is zero (P2, decided); non-zero balance
  rejects deterministically with no state change, so funds always leave
  through explicit, auditable financial operations first;
- the effect can be applied atomically in one local transaction;
- the outcome is deterministically recoverable via idempotency.

## Effect on Money Operations (no silent changes)

Today all money paths share one predicate: `Account.isOperable() == ACTIVE`,
applied under the row write lock inside the Accounts-owned contracts. That
mechanism is unchanged; only the reachable states change. With P1 decided,
the general rule is:

```text
ACTIVE either side         -> deposit/withdrawal/transfer allowed (other invariants apply)
BLOCKED either side        -> all ordinary money REJECTED (deterministic, no effects)
CLOSED either side         -> all money REJECTED (deterministic, no effects)
```

Special credits into `BLOCKED` (adjustments, refunds) are a future
capability requiring their own explicit contracts and authorization; they
MUST NOT ride on the ordinary credit path. Nothing else in
Deposit/Withdrawal/Transfer semantics changes.

## Operation Model (not a Financial Operation)

A lifecycle transition is NOT a `FinancialOperation`: it carries no amount,
no currency, no movement, and no funds invariant. It is an account-state
change with its own lifecycle:

- `CONFIRMED`: the state changed (or the idempotent no-op equivalent) and the
  audit was recorded atomically.
- `REJECTED`: deterministic refusal (unknown account, illegal transition,
  unauthorized actor, non-zero balance on close); no state change.
- `FAILED`: determined technical failure with no state change (never inferred
  from a lost response).
- `UNKNOWN`: caller-side uncertainty only, never persisted.

## Atomicity

A confirmed transition MUST atomically coordinate, within the same local
database transaction: the account state effect, the transition idempotency
record, and the audit event. No distributed transaction, no movement, no
second row. A rejected transition persists its idempotency and rejection audit
with no state change. Failure before commit leaves the state untouched.

## Deterministic Rejection, Failure, and Caller Unknown

Same discipline as financial operations, without money:

- `REJECTED`: deterministic business verdict, committed with idempotency and
  audit; replay returns the same verdict.
- `FAILED`: determined technical failure with no state change.
- `UNKNOWN`: caller-side uncertainty (committed-or-not plus lost response);
  recovery always reuses the original identity, never a new key per intent.

## Idempotency

Transitions are idempotent by identity, in a dedicated namespace per
transition kind (or one namespace keyed by account+transition — implementation
detail within these bounds):

- `Idempotency-Key` required; canonical hash over `account|transition|state`
  (exact shape deferred to implementation, SHA-256 discipline as usual).
- Same key plus same payload replays without new effects and without new
  audit (`200`, current state).
- Same key plus different payload is a conflict (`409`).
- Repeating `block` on `BLOCKED` (or `unblock` on `ACTIVE`) with a FRESH key
  is a no-op success returning the current state — state-convergence, not a
  second effect. (With the SAME key it is a plain replay.)
- `close` on `CLOSED` is always rejected, never a no-op: terminal state must
  not be re-confirmable into ambiguity.

## Concurrency

Serialization point: the same account-row write lock (`SELECT ... FOR UPDATE`
via `AccountRepository.findByIdForUpdate`, used from inside `accounts.internal`
— same module, no boundary crossing). The definitive state check and the
mutation occur under that lock in one transaction.

Consequences:

- A money operation and a transition on the same account serialize; whichever
  commits first wins deterministically (a debit that locked first sees `ACTIVE`
  and confirms; a block that locked first makes the debit reject — both valid).
- Two concurrent transitions on one account serialize; the second sees the
  first's committed state and behaves per the machine (no-op success or
  rejection).
- No lock ordering beyond one row is introduced; transfers keep their
  UUID-ordered two-row discipline unchanged.

## Authorization and Information-Leak Prevention

- `BANK_EMPLOYEE` for all transitions (recommended, P3); holder identity never
  authorizes a transition in MVP.
- Unknown account ids reject deterministically without leaking existence.
- No balances, credentials, or internals in errors or audit details beyond the
  state names, which are not sensitive.

## Audit

Minimum events, in the same local transaction as the state effect:

- `ACCOUNT_BLOCKED` / `ACCOUNT_UNBLOCKED` / `ACCOUNT_CLOSED`;
- `ACCOUNT_TRANSITION_REJECTED` (including illegal transition and unauthorized,
  without exposing more than the account id);
- `ACCOUNT_TRANSITION_CONFLICT`.

Replay creates no new effect audit. History/audit/logs separation per ADR-11
is preserved.

## API Direction

Conceptual direction, consistent with existing boundaries (`POST /api/v1/...`
plus outcome query with `Idempotency-Key-Hash`):

- `POST /api/v1/accounts/{id}/block`, `/unblock`, `/close` with
  `Idempotency-Key` and `X-Actor-Id`; `201` first effect, `200` replay/no-op,
  `400` validation, `401`/`403` auth, `409` conflict, `422` illegal
  transition, `500` technical/unknown with same-key recovery;
- `GET /api/v1/accounts/{id}` returning `accountId, holderCustomerId,
  currency, productCode, status, balanceMinorUnits, createdAt`, authorized to
  holder-or-employee; the read the lifecycle needs to be observable (today no
  account read exists);
- safe errors throughout.

Exact paths and shapes follow review; no definitive contract beyond this
direction is fixed here.

## H2 and PostgreSQL Testing Strategy

- Unit: transition matrix (all 3×3 state pairs), same-state no-op vs
  close-on-closed rejection, unknown account, unauthorized actor, hash/replay.
- Persistence: state effect plus dedicated idempotency persistence.
- Atomicity: failure injection with full rollback (no partial state/
  idempotency/audit).
- Idempotency/conflict/recovery mirroring financial operations.
- Concurrency: block-vs-debit serialization both orders (exactly one of
  {confirmed debit + later block} or {rejected debit} — never a debit on a
  committed `BLOCKED` row, never lost block); parallel same-key transitions
  converge; larger batches.
- Reconciliation: N/A for money (no movements); instead assert operability
  predicate coherence (`isOperable() == (status == ACTIVE)`) across all
  reachable states.
- API: full status matrix and safe errors, holder vs employee vs stranger.
- Architecture: ArchUnit only if new contracts need rules (expected: none
  beyond existing `accounts.api/internal/web` encapsulation; transitions stay
  inside `accounts`).
- PostgreSQL/Testcontainers validation stays a follow-up when Docker is
  available.

## Acceptance Criteria

- [ ] `block`/`unblock`/`close` confirm from legal states with state effect,
      idempotency, and audit atomically.
- [ ] Illegal transitions reject deterministically with evidence and no state
      change; `close` on `CLOSED` rejects.
- [ ] Same-state `block`/`unblock` with fresh keys succeed as no-ops without
      new effects.
- [ ] Same key plus same payload replays; same key plus different payload
      conflicts; different keys are independent intents.
- [ ] Determined technical failure records `FAILED` with no state change;
      caller-unknown recovers only via the original identity.
- [ ] Concurrent money-vs-transition serializes with no invalid final state:
      no confirmed debit on a committed `BLOCKED`/`CLOSED` row, no lost
      transition.
- [ ] `GET` account exposes status and balance to holder-or-employee with
      safe errors.
- [ ] P1, P2, and P3 closed explicitly by human approval (see below), not by
      implementation default.

## Pending Decisions (P1-P3 DECIDED by human approval; P4-P5 implementation detail)

- P1. General `BLOCKED`-credit policy — DECIDED: `REJECTED` for all ordinary
  money paths (generalizes the three transfer-scoped verdicts, least surprise,
  financial safety). Special credits (adjustments, refunds) into `BLOCKED`
  require their own future explicit contracts and authorization; they MUST NOT
  ride on the ordinary credit path. Kept here for traceability.
- P2. Close with non-zero balance — DECIDED: close requires zero balance;
  non-zero balance rejects deterministically (`REJECTED`, no state change), so
  funds always leave through explicit, auditable financial operations first. A
  future settlement-at-close phase is out of scope. Kept here for traceability.
- P3. Transition authorization — DECIDED: `BANK_EMPLOYEE` only for block,
  unblock, and close in MVP (stub role); emergency self-block is a separate
  future capability with its own authorization rules. Kept here for
  traceability.
- P4. Idempotency shape — one namespace per transition kind vs one keyed by
  account+transition; no-op-vs-replay wording on `close`-on-`CLOSED`.
  Implementation detail, no approval needed beyond review.
- P5. `GET` account scope — minimal read model above vs full history.
  Implementation detail.

## Rejected Alternatives

- Boolean `active` flag instead of states -> Rejected per ADR-03 (cannot
  express blocked/closed operability).
- Transitions as `FinancialOperation`s with movements -> Rejected: no money
  moves; polluting the financial ledger with state changes harms
  reconciliation and audit separation.
- Generic state-machine framework -> Rejected: three states with explicit
  rules need no framework.
- `BLOCKED` auto-expiry/timers -> Rejected for MVP: no temporal requirement
  exists; explicit `unblock` only.
- Cascade-closing related data (holders, beneficiaries) -> Rejected: no such
  relations exist to cascade; `CLOSED` freezes the row only.
- Sagas/events/distributed coordination -> Rejected per ADR-01/14/17: one
  local row transaction suffices.

## Contradictions and Stale References (reported, not fixed)

- None new. Known stales stand: ADR-14 `No ADR-15` line, `README.md`
  setup-only text, `MovementDirection`/`FinancialOperationStatus` javadocs,
  ADR-01 `Proposed` vs ADR-17 `Accepted`.
- No code, migration, endpoint, or build change is introduced here. Exact
  shapes (DTOs, tables beyond the existing `status` column, Java classes, HTTP
  paths beyond the direction above) are deferred to implementation within
  ADR-19 bounds.

## Notes

- Per-request intent semantics: like money, each new `Idempotency-Key` is a
  new transition intent; state-convergence no-ops apply per current state,
  not per key history.
- Operability rule with P1 decided (general, MVP scope):

```text
ACTIVE   -> deposit/withdraw/transfer allowed (other invariants apply)
BLOCKED  -> all ordinary money REJECTED (deterministic, no effects)
CLOSED   -> all money REJECTED (deterministic, no effects)
```

  The three transfer-scoped verdicts remain true under this general rule; no
  separate per-operation policy is asserted.
