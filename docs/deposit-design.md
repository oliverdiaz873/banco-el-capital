# Feature Documentation: Deposit (credit-only Financial Operation)

## Description
First Financial Operation of Banco El Capital: an authorized actor credits an explicit
amount, in the account's own currency, to one ACTIVE account. Hybrid representation per
ADR-15 (immutable movements plus stored observable balance, applied atomically), full
lifecycle per ADR-04, money-grade idempotency per ADR-06. Implemented on
`feature/deposit-financial-operation`; this document fixes the approved semantics.

## Requirements
- Functional: confirm a deposit exactly once per intent; queryable outcome after
  timeout or disconnect; balance consistent with confirmed movements at all times.
- Non-functional: single local transaction per confirmation; O(1) balance reads;
  no cross-module persistence access; safe API errors.

## Acceptance Criteria
- [ ] CONFIRMED deposit creates operation, movement, balance effect, idempotency
      record, and audit atomically.
- [ ] Same Idempotency-Key plus same payload replays without new effects and
      without new audit.
- [ ] Same key plus different payload is a conflict; no money moves.
- [ ] Different keys with identical payload are separate deposits (money has no
      business-key reuse).
- [ ] REJECTED moves no money and records no movement.
- [ ] Determined technical failure records FAILED with no movement and no
      balance change.
- [ ] Caller-unknown outcomes are recovered only via the original idempotency
      identity, never via a new key.

## Design
- Solution approach: FinOps use case coordinates validation, operability via
  Accounts, existence and auth via Identity, and confirms all effects in one
  local transaction through an Accounts-owned balance-application contract.
- Concurrency serialization: balance application write-locks the account row, so
  parallel credits on one account sequence instead of losing updates; after the
  lock is acquired the attempt re-checks the idempotency record, so a concurrent
  same-key winner that committed while waiting is replayed instead of duplicated.
  Winner reload after a uniqueness race always runs in a fresh read transaction
  (PostgreSQL aborts the whole transaction on any constraint violation, so a
  same-transaction reload could never succeed there).
- Affected components: `financialops` (new), `accounts` (operability plus
  stored balance state), `identity` (existing contracts), `audit` (events),
  `api` (`POST /api/v1/deposits` plus queryable outcome).
- Data changes: operations, movements, deposit idempotency, and balance state
  (exact shapes deferred to implementation within ADR-15 bounds); no changes to
  existing V1-V4 migrations.
- Backward compatibility: no existing API or table changes; additive only.

## Outcome Semantics
- CONFIRMED: committed operation with movement, updated balance, and audit.
- REJECTED: deterministic business refusal (unknown account, inoperable state,
  currency mismatch, invalid amount, unauthorized actor); no movement, no
  balance change, rejection audit.
- FAILED: used only when the system determines a technical failure occurred
  and the operation has no financial effect; no movement, no balance change,
  no fabricated confirmation. Never assigned merely because the client missed
  the response.
- UNKNOWN: caller-side uncertainty (e.g. committed transaction with lost
  response). Neither FAILED nor CONFIRMED may be inferred. Recovery is always
  the original Idempotency-Key (retry or outcome query), which deterministically
  returns the persisted result. A new key for recovery is forbidden because it
  would create a second deposit.
- Compatibility with ADR-04: UNKNOWN is caller knowledge, not a persisted
  lifecycle state, so ADR-04's "no formal UNKNOWN in MVP" stands. FAILED here
  is the determined-no-effect subset of ADR-04's FAILED; genuinely uncertain
  server-side states resolve through the idempotency query that ADR-04 already
  requires. No ADR-04 change.

## Implementation Plan
1. Deposit use case plus operation, movement, and idempotency persistence.
2. Accounts-owned balance application contract plus stored balance state.
3. `POST /api/v1/deposits` plus outcome query, reusing auth and error semantics.
4. Audit events (`DEPOSIT_CONFIRMED`, `DEPOSIT_REJECTED`, `DEPOSIT_CONFLICT`).
5. ArchUnit rules for the new dependencies (mirror existing style).
6. Full test layers plus concurrency proof and balance reconciliation test.
7. PostgreSQL/Testcontainers validation when Docker is available.

## Testing Strategy
- Unit tests for validation, lifecycle transitions, hash and replay logic.
- Integration tests for atomicity (operation plus movement plus balance plus
  idempotency plus audit), uniqueness, and the gateway contracts.
- API tests for the full status matrix and safe errors.
- Concurrency test: parallel same-key deposits converge to one operation.
- Reconciliation test: stored balance equals the sum of CONFIRMED movements.
- Regression: all existing Account and Customer suites stay green.

## Dependencies
- ACTIVE accounts, real holder existence, stub authorization rule, audit
  recorder, idempotency pattern: all present. Docker-dependent PG validation
  remains a follow-up.

## Risks and Mitigations
- Dual-representation divergence → Mitigation: single-transaction confirmation
  plus mandatory reconciliation tests.
- Commit-time constraint failures under PG → Mitigation: write attempt in its own
  transaction with explicit flush, winner reload in a fresh read transaction, and
  post-lock idempotency re-check; PG run as follow-up before Ship.
- Premature debit-side locking → Mitigation: the balance-holder write lock exists
  and also serves future debits; no debit logic designed now.

## Notes
- Per-request money semantics: unlike Customer identity, deposits have no
  business-key reuse; every new Idempotency-Key is new intended money.
- Operability rule for deposits (decided, MVP scope): only ACTIVE accounts may
  receive deposits.

```text
ACTIVE  -> deposit allowed (subject to all other invariants)
BLOCKED -> deposit REJECTED (deterministic business rejection, no effects)
CLOSED  -> deposit REJECTED (deterministic business rejection, no effects)
```

  Rationale: BLOCKED exists to freeze an account; frozen means frozen (least
  surprise, financial safety). The codebase already exposes a single
  operability predicate (`Account.isOperable()` == ACTIVE), giving one uniform
  rule for all money movements and keeping future withdrawals and transfers
  symmetric without a premature per-operation capability matrix. Allowing
  credits into BLOCKED accounts is deferred until a real compliance or domain
  need justifies asymmetric lifecycle semantics. This is a deposit-scoped
  verdict, not a general account-lifecycle rule: ADR-03 is unchanged, and any
  future general BLOCKED-credit policy requires its own explicit approval.
