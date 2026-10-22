# Feature Documentation: Transfer (single-currency, one operation plus two movements)

## Objective

Third Financial Operation of Banco El Capital: an authorized actor moves an explicit
positive amount, in the accounts' own currency, from one ACTIVE source account to one
ACTIVE destination account, only when sufficient funds exist at confirmation time.
Hybrid representation per ADR-15 (immutable movements plus stored observable balance,
applied atomically), full lifecycle per ADR-04, money-grade idempotency per ADR-06 in a
dedicated transfer namespace, concurrency discipline per ADR-07, transfer model per
ADR-05, debit discipline per ADR-16.

The fundamental difference from Deposit and Withdrawal:

> Distinct transfers compete for limited financial resources on two rows at once:
> the source balance (funds contention) and a deterministic lock order across rows
> (deadlock prevention).

Deposit proves monotonic credits. Withdrawal proves single-row funds contention.
Transfer proves the first multi-row invariant: concurrent transfers MUST NOT
double-spend the source balance, MUST never produce a negative balance, and MUST
never deadlock or partially apply (debit without credit).

## Implementation Status and Corrections (Phases 1-4.3)

This document began as a Phase 0/T0 design. The following facts now supersede any older wording below that conflicts with them:

- Beneficiary transfers ARE implemented (Phase 4) with an XOR request contract. The direct path remains fully supported. `TransferCommand` carries optional `beneficiaryId`; the beneficiary destination is always server-resolved and the client-supplied destination is unused on that path.
- Migrations V8 (transfer foundation), V10 (beneficiaries), V11 (beneficiary intent/usage evidence), V12 (CONFIRMED requires `operation_id`) and V13 (direct destination intent evidence) exist. There is no longer a "future V8/V11" or "conceptual V10".
- Authorization is two-phase. Phase 1 outside `writeTx` is a fast path and may perform a non-decisive idempotency lookup for an existing key. Phase 2 inside `writeTx` re-reads the authoritative source holder, calls `decideTransfer`, re-reads the owner-scoped beneficiary projection, calls `decideManageBeneficiary`, computes the effective destination and invokes the UUID-ordered account legs. The decisive idempotency re-check runs after both account locks are held by those legs and before any operation/movement/idempotency/audit write: a committed winner throws and rolls back the tentative legs. After both locks are held, the beneficiary's owner, destination and destination currency are re-read and compared with the phase-2 snapshot.
- Source authorization failure in either phase, including an unknown source inside phase 2, returns the same `403` body and persists no operation, idempotency row, rejection audit or movement. A missing/foreign beneficiary after source authorization is a business `422` with persisted intent evidence, not a source `403`.
- `AccountLookupService` and the beneficiary resolution path use projection reads for authoritative holder/beneficiary checks. Loading a managed `Account` or `Beneficiary` entity before the locked legs could otherwise let stale first-level-cache state lose a concurrent committed update or hide a post-lock change.
- The service-owned `writeTx` pins `READ_COMMITTED` and uses `REQUIRED`: it joins an ambient caller transaction, so the caller can determine effective isolation and final rollback; a successful inner return does not guarantee a commit. Three paths use `REQUIRES_NEW`: (a) a second-leg refusal persists its rejection in a fresh balance-free write transaction so an outer rollback-only caller cannot suppress it; (b) conflict evidence is written in a fresh write transaction; (c) winner reload after a failed write runs in a fresh read-only transaction because PostgreSQL aborts the whole transaction after a constraint violation. Ordinary business rejections before any leg run inside `writeTx` and therefore commit or roll back with the caller.
- The intent hash uses a path discriminator: direct keeps `source|destination|amount|currency`; beneficiary uses `beneficiary-transfer|source|beneficiaryId|amount|currency`. V12 enforces `outcome <> 'CONFIRMED' OR operation_id IS NOT NULL` and aborts with a preflight error if historical rows violate it, without repair. V13 adds `destination_account_id` to `transfer_operation_idempotency` (never to `financial_operations`) with a validated scope check and a `NOT VALID` direct-required check; no backfill. New direct `CONFIRMED`/`REJECTED` rows persist the supplied destination, beneficiary rows keep it `NULL`, and replay compares it when present while pre-V13 rows remain hash-only.
- The acceptance checkboxes at the end are historical. Phase 4.3 does not claim a global acceptance beyond the tests and evidence reported for the implemented scope.

## Scope

In scope: single-currency transfer validation, sufficient-funds invariant on the
source, deterministic lock ordering between the two accounts, dedicated use of the
Accounts-owned credit and debit contracts, transfer lifecycle and idempotency in a
separate namespace, single-transaction atomicity over two balances, minimal audit,
conceptual API direction, invariant-based testing, and H2-transitory risk
documentation. Direct and saved-beneficiary transfers are both implemented;
beneficiary ownership, resolution and post-lock revalidation are included per
ADR-21.

Out of scope: cross-currency and FX, fees, overdraft and limits, compensation/reversal
execution, account lifecycle transitions (block/close), formal ledger/double-entry,
event sourcing, sagas, Kafka/Redis/microservices/distributed transactions, real
authentication (JWT/OAuth/Spring Security), and advanced observability.

## Actors, Source and Destination

- Actor: authenticated actor under the current stub (self or `BANK_EMPLOYEE` via
  `X-Actor-Id`). Authorization is always evaluated against the source holder;
  on the beneficiary path, `decideManageBeneficiary` is additionally required
  against the persisted beneficiary owner. The request carries no holder or
  owner identity; both are server-resolved.
- Source: account that owns the funds. MUST exist, MUST be `ACTIVE`, MUST have a
  currency equal to the operation currency, MUST have `available >= amount` at
  confirmation time. If the actor cannot prove source authority, the request is
  a uniform `403` with no persisted evidence, whether the source is unknown or
  owned by somebody else.
- Destination (direct path): account that receives the funds. MUST exist, MUST
  be `ACTIVE` (T0 decision, approved, transfer-scoped): `BLOCKED` and `CLOSED`
  destinations reject deterministically. This verdict does not change ADR-03
  generally; it is the transfer-scoped symmetric rule already used by Deposit
  and Withdrawal.
- Destination (beneficiary path): resolved exclusively from the saved
  beneficiary owned by the actor. Missing/foreign beneficiary is a generic
  business `422` after source authorization; the resolved destination is then
  checked under the same operability/currency/funds rules.
- Source and destination MUST be distinct accounts (`source != destination`).
  Self-transfer to the same account id is a deterministic business rejection
  once source authorization succeeds.

## Preconditions and Business Rules

A transfer confirms only when all hold:

- source and destination ids are present and distinct;
- the source exists and the actor is authorized on its holder under the current
  stub (`actor == source holder` or `BANK_EMPLOYEE`). Failure is a uniform
  `403` with no persisted evidence, not a `REJECTED` operation;
- the destination exists, checked only after source authorization advances
  (unknown destination is a deterministic `422` with persisted intent);
- on the beneficiary path, `decideManageBeneficiary` also allows the persisted
  beneficiary owner;
- both accounts are operable under transfer scope (`ACTIVE`; `BLOCKED`/`CLOSED`
  on either side reject);
- amount is a positive minor-units value (`long`, `> 0`);
- currency is explicit (`^[A-Z]{3}$`) and equals both the source and the
  destination currency (single-currency MVP, no FX);
- sufficient funds: `source available balance >= requested amount`, verified
  after acquiring both row locks inside the same transaction;
- the full effect (operation + two movements + two balances + idempotency +
  audit) can be applied atomically in one local transaction;
- the outcome is deterministically recoverable via idempotency.

Exact-balance transfer is allowed (T0 precision, approved): moving the full
available source balance leaves source at zero and confirms, mirroring
Withdrawal exact-balance semantics. Zero source balance does not allow any
positive transfer. Insufficient funds is a deterministic business rejection
(`REJECTED`), not a technical failure: no movement, no balance change on either
side.

## Operation Lifecycle

Transfer uses the ADR-04 lifecycle without new states:

- `CONFIRMED`: committed operation with one `DEBIT` movement on the source and
  one `CREDIT` movement on the destination, both balances updated, and audit.
- `REJECTED`: deterministic business refusal reached after source authorization
  (unknown destination, same-account, inoperable state on either side, currency
  mismatch, invalid amount, insufficient funds, missing/foreign beneficiary); no
  movement on either side, no balance change, rejection audit and intent
  evidence. Source authorization failure is NOT a `REJECTED` operation: it is a
  uniform `403` with no persisted evidence.
- `FAILED`: used only when the system determines a technical failure occurred
  and the operation has no financial effect (for example arithmetic overflow via
  `addExact`/`subtractExact`); no movement, no balance change, no fabricated
  confirmation. Never assigned merely because the client missed the response.
- `UNKNOWN`: caller-side uncertainty (committed-or-not plus lost response).
  Neither `FAILED` nor `CONFIRMED` may be inferred. Recovery is always the
  original identity.
- `PENDING` remains conceptual/transitory, not a persisted stable state in this
  increment, consistent with the Deposit and Withdrawal implementations.

Compatibility with ADR-04 stands: no formal `UNKNOWN` in MVP; `FAILED` here is
the determined-no-effect subset; uncertain server-side states resolve through
the idempotency query that ADR-04 already requires. No ADR-04 change.

## Operation and Its Two Movements

- One confirmed transfer carries exactly one `FinancialOperation` of type
  `TRANSFER` plus exactly two `Movement` rows sharing the same `operation_id`:
  `DEBIT` (source, amount, currency) and `CREDIT` (destination, amount,
  currency), each with owning operation, account, and timestamp.
- Two-account representation, Option A — DECIDED in T0.2 approval, no schema
  migration (B4): the existing `financial_operations` schema is kept unchanged.
  `FinancialOperation.account_id` identifies the SOURCE account. The `DEBIT`
  movement references the source account; the `CREDIT` movement references the
  destination account; both movements share the `operation_id` of the single
  `TRANSFER` operation. The destination is therefore reconstructed from the
  linked `CREDIT` movement, not read directly from `FinancialOperation`.
  Advantage: existing financial representation is preserved and MVP scope is
  reduced. Trade-off: obtaining the destination requires querying the linked
  movements instead of reading it directly from `FinancialOperation`. No
  `destination_account_id` column is introduced in `financial_operations`;
  V13 adds one only to `transfer_operation_idempotency` as replay intent
  evidence.
- Both movements belong to the same operation and represent one financial unit.
  Two independent operations (one debit plus one credit) are forbidden per
  ADR-05. No `DEBITED`/`CREDITED`/`PARTIALLY_COMPLETED` states. Confusing
  origin with destination is impossible by construction: `DEBIT.account_id`
  MUST equal `operation.account_id` (source) and `CREDIT.account_id` MUST equal
  the transfer destination; any other combination is invalid.
- Movements are created only when the operation confirms; immutable afterwards:
  never updated, never deleted. Correction happens through a future
  compensatory operation, not by editing history (out of scope here).
- Reconciliation is diagnostic only, never the read path, per account:

```text
balance(source) = sum(confirmed credits on source) - sum(confirmed debits on source)
balance(destination) = sum(confirmed credits on destination) - sum(confirmed debits on destination)
```

Balance reads stay O(1) on stored state per ADR-15. Aggregation over movements
is the integrity check on both legs.

## Atomicity of Both Balances and Movements

A confirmed transfer MUST atomically coordinate, within one single outer local
write transaction (`writeTx`), all of: operation state (`TRANSFER`), two movement
creations (one `DEBIT` on the source plus one `CREDIT` on the destination sharing
the same `operation_id`), both stored balance effects, transfer idempotency state,
and audit event. No distributed transaction.

Transaction composition (B1): the outer `writeTx` is the only committing unit.
`AccountDebitService.applyDebit` and `AccountCreditService.applyCredit` (both
`@Transactional`, therefore `REQUIRED` propagation) MUST join that same outer
transaction, never commit independently. There MUST be no independent commit and
no confirmation point between the debit leg and the credit leg: if the second
leg returns a non-`APPLIED` outcome or throws, the first leg's balance effect
rolls back with the outer transaction.

`flush` versus commit: `flush` only forces pending SQL to the database inside
the transaction so constraint violations surface early; it is NOT a commit and
it does not release row locks. No unnecessary partial flushes are introduced
between the two legs; a single explicit `flush` before returning the
confirmation decision (mirroring Deposit/Withdrawal) suffices to surface
uniqueness and integrity errors inside the same `writeTx`, with winner reload
in a fresh read transaction afterwards.

A rejected transfer persists operation, idempotency, and rejection audit with no
movement and no balance change on either side. A rejection decided before any
leg (same-account, unknown account, or a first-lock refusal) is written inside
the main `writeTx` and follows the caller's commit/rollback. If the second leg
refuses after the first leg mutated a balance, the main `writeTx` rolls back and
the rejection is persisted in a fresh balance-free `writeTx` (`REQUIRES_NEW`), so
an outer rollback-only caller cannot suppress the deterministic evidence.

Failure between debit and credit MUST revert all effects (T0 precision,
approved). Because debit and credit are applied inside one local transaction,
there is no commit point between them:

- failure before commit (exception in either `Accounts` contract, movement
  persistence, idempotency persistence, audit persistence, `flush`, arithmetic
  overflow, constraint violation) rolls back the whole confirmation;
- a committed `CONFIRMED` operation always carries its full effects
  (operation + DEBIT + CREDIT + both balances + idempotency + audit);
- failure while persisting idempotency or audit rolls back the whole
  confirmation.

There is no valid partially applied confirmed result:

- no source debit without destination credit;
- no destination credit without source debit;
- no balance change without its movement;
- no confirmed movement without its balance effect;
- no `CONFIRMED` operation without both effects;
- no `CONFIRMED` idempotency without both effects;
- no `CONFIRMED` audit without both effects.

How this is demonstrated (design requirement, not implementation here):

- H2: failure-injection tests that throw between the debit application and the
  credit application (or between balance effects and movement persistence) MUST
  assert zero `financial_operations` rows, zero `movements` rows on either side,
  zero `transfer_operation_idempotency` rows, zero `CONFIRMED` audit rows, and
  both stored balances intact — mirroring
  `DepositAtomicityTest` / `WithdrawalAtomicityTest`. The injected failure MUST
  occur after the first leg's balance mutation but before outer commit, proving
  the outer rollback discards it.
- PostgreSQL/Testcontainers: the same between-legs rollback proof MUST run
  against real PostgreSQL (`postgres:16-alpine`), because PostgreSQL aborts the
  whole transaction on constraint violation and has distinct lock/visibility
  semantics. The PG test MUST assert the same zero-effect outcome plus both
  balances unchanged, and MUST be part of the `*Postgres*` gate (executed, none
  skipped). H2 alone is transitory evidence.

## Deterministic Rejection, Confirmed-No-Effect Failure, and Caller Unknown

Three disjoint outcomes, following Deposit/Withdrawal discipline:

- `REJECTED`: deterministic business verdict reached inside the write unit and
  committed as operation + idempotency `REJECTED` + rejection audit. Replay of
  the same key plus same payload returns the same rejection without new effects
  when the persisted source holder can still be resolved and the actor is
  authorized under the ADR-21 recovery rules. If the persisted source is
  unknown or unresolvable, POST recovery fails closed with `403 FORBIDDEN`
  without replaying; possession of the key and payload is not authorization.
  Examples after source authorization succeeds: unknown destination,
  same-account, inoperable either side, currency mismatch, invalid amount,
  insufficient funds, missing/foreign beneficiary. A source that is unknown or
  not authorized is NOT a `REJECTED` operation: it is a uniform `403` that
  persists no operation, idempotency row, rejection audit or movement.
- `FAILED`: determined technical failure with no financial effect, committed as
  operation + idempotency `FAILED` only when the system positively determines
  no effect occurred (for example `ArithmeticException` from
  `addExact`/`subtractExact`). Never inferred from a lost response.
- `UNKNOWN`: caller-side uncertainty (committed-or-not plus lost response, or
  `DataIntegrityViolationException` where the winner cannot be reloaded).
  Never persisted. Recovery is always the original `Idempotency-Key` (retry or
  outcome query). A new key for recovery is forbidden because it would create a
  second transfer.

## Idempotency, Replay and Conflicts

Same discipline as Deposit/Withdrawal, separate transfer namespace
(`transfer_operation_idempotency`, created by V8 and extended by V11/V12/V13; no
migration in T0):

- `Idempotency-Key` required; canonical request hash defined exactly as follows
  (B3). Normalization before hashing:
  - source: `sourceId.toString().toLowerCase()` (canonical UUID string with
    hyphens, lowercase);
  - destination: `destinationId.toString().toLowerCase()` (same form);
  - amount: `Long.toString(amountMinorUnits)` (base-10, no grouping, no decimals);
  - currency: three-letter ISO code uppercased (`currency.toUpperCase()`).
  Canonical payload string (exact field order, `|` separator, no whitespace):

```text
source-lowercase-uuid|destination-lowercase-uuid|amount-base10|currency-upper
beneficiary-transfer|source-lowercase-uuid|beneficiary-lowercase-uuid|amount-base10|currency-upper
```

  Bytes: UTF-8 encoding of that string. Hash: SHA-256 over those bytes, hex
  encoded lowercase (64 chars). The direct path keeps its existing shape; the
  beneficiary path adds the mandatory discriminator so a direct intent and a
  beneficiary intent can never share an identity. The resolved beneficiary
  destination is execution evidence, not intent identity. The same logical
  intent MUST produce the same hash on every retry; any deviation in
  normalization is a conflict risk, not a replay.
- Authorized same key plus same payload on a confirmed transfer replays without
  new effects and without new audit of the original effect (`200`,
  `created=false`).
- Authorized same key plus same payload on a rejected transfer returns the same
  deterministic rejection without new effects. If the persisted source holder
  cannot be resolved, POST recovery fails closed with `403 FORBIDDEN` and
  replays nothing (ADR-21).
- Same key plus different payload is a conflict (`409`); no money moves and
  idempotency state is untouched; conflict evidence (`TRANSFER_CONFLICT`) is
  recorded in its own write unit.
- Different keys with identical payload are separate transfer intents; each
  competes for source funds at its own serialization point (the second may
  reject for insufficiency even if the first confirmed).
- Retry after timeout/disconnect reuses the original key or the outcome query;
  a new key for recovery is forbidden because it would create a second
  transfer.
- Post-lock idempotency re-check is mandatory and occurs only after BOTH row
  locks are held, and after the two legs have been invoked: their tentative
  effects are rolled back if a committed winner is found, then the waiter
  replays it instead of duplicating. The second-leg path also re-checks
  idempotency before converting a refusal into a fresh rejection. Winner reload
  after a uniqueness race runs in a fresh read transaction (PostgreSQL aborts the
  whole transaction on any constraint violation, so a same-transaction reload
  could never succeed there).
- Recovery: the outcome query (`GET /api/v1/transfer-operations/{key}`
  direction) requires the `Idempotency-Key-Hash` (the 64-char hex above) and
  authorizes against the server-resolved SOURCE holder (self) or
  `BANK_EMPLOYEE` under the current stub. The destination holder alone MUST
  NOT recover the operation nor learn its result. Denied responses (`401` /
  `403` / `404`) MUST NOT reveal operation ids, holders, balances, or
  internals of a foreign operation.

## Deterministic Lock Order and Deadlock Prevention

Serialization point: the two account-row write locks (`SELECT ... FOR UPDATE`
semantics through the Accounts-owned contracts). Parallel transfers touching
overlapping accounts sequence instead of producing invalid states.

Ordering discipline (T0 decision, approved) — chosen mechanism compatible with
the current public contracts and ADR-17 (B2):

Inspected: `AccountDebitService.applyDebit` and `AccountCreditService.applyCredit`
are both `@Transactional` (`REQUIRED`) and each acquires exactly one row write
lock synchronously via `AccountRepository.findByIdForUpdate` at call time, then
re-verifies existence, operability, currency (plus sufficient funds on the debit
leg) before mutating. `AccountLookupService.findHolderCustomerId` is read-only
(`findById`, no lock) and stays a non-decisive fast path. No `accounts.internal`
access from `financialops.transfer`, no dependency on `deposit`/`withdrawal`.

Chosen option: invoke the two Accounts-owned contracts in deterministic UUID
ascending order, regardless of transfer direction. Concretely, let
`first = min(sourceId, destinationId)` and `second = max(sourceId, destinationId)`
by UUID natural ordering; call the contract belonging to `first` first (debit if
`first` is the source, otherwise credit), then the contract belonging to
`second`. Because each call locks exactly its row synchronously, call order IS
lock order here — ordering the calls in UUID order guarantees both locks are
acquired in UUID order. This claim holds only because each contract locks one
row at call time; it would NOT hold for contracts that defer locking.

Rules:

1. Optional fast-path reads (existence via lookup, idempotency pre-check) do
   not decide.
2. Enter the single outer `writeTx`; invoke the two contracts in UUID ascending
   order and hold both row locks until outer commit — locks are never released
   between legs.
3. Post-lock idempotency re-check only after BOTH locks are held and both leg
   calls have returned: if a concurrent same-key winner committed while waiting
   for either lock, throw and roll back the tentative effects, then replay the
   winner instead of duplicating it.
4. The confirmation decision (`CONFIRMED` versus `REJECTED`) is taken only after
   BOTH contract results are known. Per-leg checks (existence, operability,
   currency, funds) each occur under their own row lock and stay valid because
   that lock is held continuously to commit; the business verdict is still
   committed only after both legs report. No pre-lock balance read substitutes
   for this locked decision.
5. Apply the debit (source) and the credit (destination) effects and confirm all
   effects in the same outer transaction; a non-`APPLIED` result on either leg
   rejects the whole transfer with no movement and no balance change on either
   side.

Implications: no change to `accounts.api` signatures and no new lock method are
required; the cost is that the debit/credit invocation order varies with UUID
order rather than always debit-first, which callers MUST NOT interpret as an
economic order — the economic effect is simultaneous at commit. No impediment
requiring an incompatible contract change was found; if review prefers an
explicit `lockBothInUuidOrder` contract for readability, that is a minimal
additive `accounts.api` proposal for human approval, NOT decided here.

Guarantee offered: any two concurrent transfers over the same pair acquire the
two row locks in the same global order, so opposite transfers A->B and B->A
cannot deadlock each other by holding one lock and waiting for the other in
opposite order. Contention on disjoint pairs proceeds in parallel. Proof is by
test, not by assertion: opposite-transfer concurrency tests on H2 plus
PostgreSQL MUST show both complete (one or both confirm depending on funds,
never deadlock, never negative, never partial).

Explicit scenarios (all in minor units):

- `A=10000, B=0` with concurrent `A->B 7000` + `A->B 7000`: exactly one
  confirms, the other rejects for insufficient funds; final `A=3000, B=7000`.
  Never negative, never two confirmed.
- `A=10000, B=0` with concurrent `A->B 4000` + `A->B 6000`: serialization
  order permitting, both confirm; final `A=0, B=10000`.
- Opposite `A=10000, B=10000` with concurrent `A->B 6000` + `B->A 6000`: with
  UUID-ordered locks both complete without deadlock; final balances reflect
  the serialization order (`A=10000, B=10000` if both confirm).
- Same key concurrent `A->B 5000` twice: exactly one effect (1 operation +
  2 movements), the other replays.

No lock ordering beyond two rows is introduced. Beneficiary transfers with more
participants are out of scope.

## Authorization and Information-Leak Prevention

Current stub is retained, no new auth design (ADR-09):

- authenticated actor required (`X-Actor-Id` present and non-blank);
- authorization always requires source authority: `actor == source holder` or
  `BANK_EMPLOYEE` role. On the beneficiary path ADR-21 additionally requires
  `decideManageBeneficiary` against the persisted beneficiary owner. The
  destination holder never authorizes (consistent with Option A:
  `operation.account_id` = source);
- both holders and the beneficiary owner are server-resolved; the request
  carries no holder/owner identity, so a mismatched holder cannot satisfy auth;
- an unknown source and a foreign source return the same `403` body, with no
  operation, idempotency row, rejection audit or movements. Once source
  authority is proven, deterministic business failures (including an unknown
  destination or missing/foreign beneficiary) remain `422` with persisted
  intent evidence;
- authorization is evaluated twice: a phase-1 fast path and an authoritative
  phase-2 re-evaluation inside `writeTx` before any financial effect. A
  beneficiary is re-read again after both account locks are held;
- outcome query (`GET /api/v1/transfer-operations/{key}` direction) MUST be
  authorized against the server-resolved source holder and guarded by the
  request hash; unauthorized callers receive `401`/`403`/`404` without learning
  the operation id, balances, holders, or internals;
- insufficient-funds and other rejections MUST NOT expose balances;
- no JWT/OAuth/OIDC, Spring Security, sessions/tokens, MFA, or full RBAC in
  this increment.

## Audit

Minimum events, mirroring deposits/withdrawals:

- `TRANSFER_CONFIRMED`;
- `TRANSFER_REJECTED` (including insufficient funds and inoperable either
  side, without exposing balances);
- `TRANSFER_CONFLICT`.

Replay creates no new effect audit. Conflict evidence is recorded in its own
write unit without mutating idempotency state. All confirmation/rejection
evidence is written in the same local transaction as the financial effects
(rollback leaves no false trail). No full balances, credentials, tokens, PII,
or internals are logged unnecessarily. History/audit/logs separation per
ADR-11 is preserved.

## H2 and PostgreSQL Testing Strategy

Invariant-based, mirroring Deposit/Withdrawal layers plus two-row contention:

- Unit: source/destination validation, same-account rejection, sufficient/
  insufficient/exact-balance, zero source, currency mismatch either side,
  unknown either side, non-operable either side, arithmetic boundaries,
  authorization on source, hash and replay logic.
- Persistence: transfer operation with `account_id` = source, linked DEBIT
  (source) + CREDIT (destination) movements sharing one `operation_id`, both
  balances persisted, dedicated idempotency persistence,
  namespace isolation (same key usable in deposit/withdrawal namespaces
  without collision). Persistence tests MUST verify each confirmed transfer
  has exactly those two linked movements and that origin/destination cannot be
  confused (`DEBIT.account_id == operation.account_id == source`,
  `CREDIT.account_id == destination`).
- Atomicity: failure injection between debit and credit (and between effects
  and idempotency/audit) with full rollback assertions on H2; duplicate proof
  on real PostgreSQL (see Atomicity section). No partial operation/movement/
  balance/idempotency/audit.
- Idempotency: same-key/same-payload (confirmed and rejected), same-key/
  different-payload conflict, different keys as separate transfers, retry and
  `UNKNOWN` recovery via original key or outcome query.
- Concurrency: same-key convergence to one effect (1 op + 2 movs); different
  keys with sufficient funds all confirm; competing transfers exceeding source
  funds confirm only those preserving `balance >= 0`; opposite transfers
  complete without deadlock; proof that no negative balance and no partial
  transfer ever appear; larger parallel batches.
- Reconciliation: per-account `balance == credits - debits` over mixed
  histories including transfers; rejections leave both balances and movements
  untouched.
- API: full status matrix (`201` new, `200` replay, `400` validation, `401`
  unauthenticated, `403` forbidden, `409` conflict, `422` deterministic
  business rejection including insufficient funds and inoperable either side,
  `500` technical/unknown with same-key recovery message) and safe-error
  behavior, including authorized versus unauthorized outcome queries.
- Architecture: extend ArchUnit only if the new transfer contracts require new
  rules; expected rules are `transfer !<-> deposit/withdrawal` isolation,
  `core` not depending on transfer, `financialops -> accounts.api` only (no
  `accounts.internal`/`accounts.web`), `web` not touching repositories or
  entities. No rule without an invariant reason. Regression on all existing
  suites.

## Acceptance Criteria

- [ ] `CONFIRMED` transfer creates operation, two linked movements, two balance
      effects, idempotency record, and audit atomically, with Option A
      representation: `FinancialOperation.account_id == sourceAccountId` and
      `operation_type == TRANSFER`; exactly one `DEBIT` movement with
      `account_id == sourceAccountId`; exactly one `CREDIT` movement with
      `account_id == destinationAccountId`; both movements carry the same
      `operation_id` as the single `TRANSFER` operation; origin and
      destination cannot be confused; no `destination_account_id` column is
      introduced in `financial_operations` (V13 adds it only to the transfer
      idempotency row).
- [ ] Same `Idempotency-Key` plus same payload replays without new effects and
      without new audit when recovery authorization succeeds; otherwise it
      fails closed with `403 FORBIDDEN` per ADR-21, without replay and without
      new effects.
- [ ] Same key plus different payload is a conflict; no money moves.
- [ ] Different keys with identical payload are separate transfers (the second
      may reject for insufficiency even if the first confirmed).
- [ ] `REJECTED` moves no money on either side and records no movement.
- [ ] Determined technical failure records `FAILED` with no movement and no
      balance change on either side.
- [ ] Caller-unknown outcomes are recovered only via the original idempotency
      identity, never via a new key.
- [ ] Same-account transfer is rejected deterministically.
- [ ] `BLOCKED`/`CLOSED` on either side is rejected deterministically.
- [ ] Exact-balance transfer (source to zero) confirms.
- [ ] Opposite concurrent transfers complete without deadlock, without
      negative balances, and without partial application.
- [ ] Failure injected between debit and credit leaves zero effects and both
  balances intact on H2 and on real PostgreSQL.

## Pending Decisions and Rejected Alternatives

Pending (require human approval before implementation; documented here instead
of decided silently):

- P1. Exact-balance allowed — DECIDED in T0 approval (allowed, mirror
  Withdrawal). Kept here for traceability.
- P2. Destination `BLOCKED`/`CLOSED` — DECIDED in T0 approval (reject,
  transfer-scoped, ADR-03 unchanged). Kept here for traceability.
- P3. Base authorization on the source holder — DECIDED in T0 approval and
  kept for traceability. ADR-21 (Accepted) adds beneficiary-owner authorization
  on the beneficiary path; that extension is implemented in Phase 4.
- P4. UUID-ordered lock acquisition — DECIDED in T0 approval. Kept here for
  traceability.
- P5. Retention/cleanup policy for `transfer_operation_idempotency` and audit
  evidence — OPEN, follows ADR-06 retention OPEN; not decided in T0.
- P6. Index selection and `CHECK` constraints (`amount > 0`,
  `balance >= 0`) — OPEN for implementation within ADR-15 bounds; MUST stay
  PostgreSQL/H2 compatible and MUST NOT violate module ownership.
- P7. Two-account operation representation — DECIDED in T0.2 approval:
  Option A, no schema migration. `FinancialOperation.account_id` = source;
  destination via linked `CREDIT` movement. Recovery and authorization use the
  source account and its server-resolved holder; being solely the destination
  holder grants no access to the result. Kept here for traceability.

Rejected alternatives (with reason):

- Two independent operations (debit op + credit op) -> Rejected per ADR-05:
  harms atomicity, idempotency, and reconciliation.
- Adding a `destination_account_id` column to `financial_operations` (migration
  V8 for a two-column operation) -> Rejected for Transfer MVP (T0.2): Option A
  reconstructs the destination from the linked `CREDIT` movement with the
  existing schema, reducing MVP scope. A dedicated destination column MAY be
  reconsidered later if query/traceability needs justify it.
- Source-then-destination lock order -> Rejected: opposite transfers can
  deadlock; UUID order gives a global order with the same cost.
- Pre-lock funds check as decision -> Rejected: balance can go stale before
  effects apply; definitive check MUST be post-lock (ADR-16 discipline).
- Optimistic check without row locks -> Rejected: leaves the two-row
  validation-plus-effect unit without a proven serialization point.
- Shared deposit/withdrawal idempotency namespace -> Rejected: conflates
  distinct monetary intents; same discipline, separate namespace.
- Single generic credit/debit applicator across transfer legs -> Rejected:
  source leg needs funds check, destination leg does not; reuse the two
  dedicated Accounts-owned contracts instead.
- Formal ledger/double-entry now -> Rejected for MVP per ADR-02/ADR-16;
  movements stay the effect record and future ledger base.
- Beneficiary resolution inside transfer -> Rejected here: no beneficiary
  model exists; beneficiary transfer is a later increment on top of this one.
- Cross-currency/FX, fees, overdraft -> Rejected for MVP: no product need;
  single-currency only.
- Sagas, events, queues, distributed transactions, microservices -> Rejected
  per ADR-01/ADR-14/ADR-17: one local transaction suffices.

## Contradictions and Stale References Affecting Transfer (reported, not fixed)

- ADR-14 `No ADR-15 is created now` is stale versus ADR-15/16/17 existence.
  Transfer does not depend on that line; recorded only.
- `README.md` still describes setup without banking functionality, stale
  versus Deposit/Withdrawal. Not modified in T0.
- `MovementDirection` javadoc ("credits only") and `FinancialOperationStatus`
  javadoc ("subset for deposits") are stale versus Withdrawal. Not modified
  in T0.
- ADR-01 `Proposed` with addendum to ADR-17 `Accepted` — state inversion
  noted; Transfer follows ADR-17 boundaries regardless.
- No code, migration, endpoint, or build change is introduced here. The only
  foreseen shared-model change for implementation is adding `TRANSFER` to the
  `FinancialOperationType` enum in `financialops.core`; `core` MUST NOT depend
  on the `transfer` package. Exact shapes (table/column/index, Java classes,
  HTTP paths beyond the direction above) are deferred to implementation within
  ADR-18 bounds.

## Notes

- Per-request money semantics: like Deposits and Withdrawals, transfers have
  no business-key reuse; every new `Idempotency-Key` is a new intended money
  movement even if source/destination/amount/currency are identical.
- Operability rule for transfers (decided, MVP scope): only `ACTIVE -> ACTIVE`
  transfers confirm.

```text
source ACTIVE + destination ACTIVE -> transfer allowed (subject to all other invariants)
source BLOCKED/CLOSED            -> transfer REJECTED (deterministic, no effects)
destination BLOCKED/CLOSED       -> transfer REJECTED (deterministic, no effects)
source == destination            -> transfer REJECTED (deterministic, no effects)
```

  Rationale: frozen/closed means frozen/closed (least surprise, financial
  safety), symmetric with Deposit/Withdrawal. This is a transfer-scoped
  verdict, not a general account-lifecycle rule: ADR-03 is unchanged, and any
  future general `BLOCKED`-credit policy requires its own explicit approval.
