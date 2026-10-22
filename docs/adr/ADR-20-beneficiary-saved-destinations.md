# ADR-20: Beneficiary Saved Destinations (v1, Transfer-untouched)

## Context

Accounts are operable through `isOperable() == ACTIVE`
(`accounts/internal/Account.java:102-104`) with lifecycle transitions owned by
Accounts (ADR-19). Transfers move value between two ACTIVE accounts using
account UUIDs directly, with holders server-resolved and authorization on the
source holder only (`TransferService.transfer():102-134`, ADR-18). There is no
`beneficiaries` production code; ADR-17 lists it as an empty placeholder and
ADR-01 assigns it the Customer relationship, authorized destination
references, and Account resolution when needed (it does NOT own Accounts).
ADR-05 defines Beneficiary as a reference owned by Beneficiaries (NOT an
Account), resolved/validated at operation time, with traceability of which
beneficiary was used when one is used — while leaving direct-versus-
beneficiary-only open and external transfers out.

The system needs saved destinations without touching the proven money paths.
This ADR fixes the v1 beneficiary decision within those bounds, without
designing external destinations, alias editing, logical-deletion states,
pagination, employee management, Transfer execution changes, ledger,
authentication, observability, PostgreSQL validation work itself, or
distributed machinery. Human-approved inputs P1-P8 plus precisions A-C bound
this decision (see `docs/beneficiary-design.md`).

## Decision

Adopt owner-scoped saved internal-account references as the fifth Banco El
Capital capability, Transfer-untouched (Alternative A):

- A beneficiary is a `(owner_customer_id, destination_account_id, alias,
  destination_currency snapshot)` reference owned by Beneficiaries. It is
  never an Account, never a FinancialOperation, never a Movement.
- v1 operations: create (idempotent), list (owner only), get by id (owner
  only), delete (physical + audit). Alias immutable in v1; alias duplicates
  across different destinations of the same owner are allowed.
- Uniqueness: `UNIQUE(owner_customer_id, destination_account_id)` only. No
  `UNIQUE(owner, alias)`.
- Scope: internal accounts only. Saving validates existence + ISO currency
  via a minimal Accounts-owned read contract shaped as
  `findDestination(UUID) -> Optional<DestinationCurrency>` (presence =
  exists with currency inside; absence = unknown; no incoherent
  exists-without-currency state representable; payload carries neither
  holder, status, nor balance). Saving MUST NOT require `ACTIVE`:
  operability is observed at financial-operation time under row locks. A
  product rule rejecting blocked destinations at save time would need its
  own approval.
- Alias: normalized once (trim, 1..64 after trim, no control characters;
  inner spacing and case preserved as trimmed) and persisted verbatim; the
  canonical hash uses the NORMALIZED alias (see design doc). No
  `UNIQUE(owner, alias)`.
- Authorization: owner server-resolved from `AuthenticatedActor.subject()`
  BEFORE any idempotency read/write; `decideManageBeneficiary` denials
  (`UNAUTHENTICATED`/`FORBIDDEN`) persist nothing and audit nothing (ordering
  per `AccountCreationService.create():62-65`,
  `DepositService.deposit():98-101`). New
  `AuthorizationService.decideManageBeneficiary` with stub semantics
  self-or-`BANK_EMPLOYEE`; v1 default self-only (REST builds actors with
  empty roles today). The `X-Actor-Id` header alone is self-asserted and
  spoofable (dev stub per ADR-09, constructions at `TransferController:53`,
  `DepositController:45`); it is accepted for MVP only with owner-scoped,
  disclosure-safe semantics and no expanded auth scope.
- HTTP (bounds, full matrix in design doc): `POST` 201/200 with
  `{ beneficiaryId, created }`; `GET` list/get owner-only with
  `{ beneficiaryId, alias, destinationAccountId, destinationCurrency,
  createdAt }`; `DELETE` `204 No Content` with empty body; `DELETE` 204; recovery `GET /beneficiary-operations/{key}`
  with `{ beneficiaryId | null, outcome }`; error mapping
  401/403/422/409/500 mirroring `TransferExceptionHandler:23-30` /
  `DepositExceptionHandler:22-29`; missing-or-foreign reads share one `404`;
  responses never carry holder identity, balance, or status.
- Idempotency: dedicated `beneficiary_creation_idempotency` namespace with
  canonical hash `owner-lowercase|destination-uuid-lowercase|alias-normalized-
  verbatim`, UTF-8, SHA-256 hex (alias leg NOT lowercased: case-distinct
  aliases are distinct intents; surrounding whitespace is trimmed before
  hashing so it replays). Persisted outcomes are exactly `CONFIRMED`
  (with `beneficiary_id`) and `REJECTED` (with `beneficiary_id NULL`);
  `FAILED`/`UNKNOWN`/`CONFLICT` are never persisted as outcomes, and
  auth denials persist nothing. Same key + same hash replays (`CONFIRMED`
  returns `created=false`; stored `REJECTED` rethrows `REJECTED`); same key +
  different payload conflicts with its own audit and untouched idempotency
  state. Duplicate `(owner, destination)` under DIFFERENT keys is a new
  intent and persists its own `REJECTED` ("duplicate destination"). No
  idempotency namespace for delete; delete of the same id twice is
  deterministically `404`.
- Concurrency (PostgreSQL-safe): attempt in exactly one outer `writeTx`
  (`TransactionTemplate`); winner reload after `DataIntegrityViolationException`
  in a SEPARATE fresh read-only `readTx` against committed state — the
  `DepositService:37-41,112-130` / `TransferService:49-54,172-194` /
  `AccountTransitionService:31-35,97-114` pattern, required because PostgreSQL
  aborts the whole transaction on constraint violation. Same-transaction
  reload (older `AccountCreationService.create():72-84` shape) MUST NOT be
  copied. Explicit flush inside `writeTx` surfaces uniqueness violations
  deterministically (`DepositService.attempt():227-230`). No account row
  locks: the `UNIQUE(owner, destination)` constraint is the arbiter.
- Delete: physical row removal plus `BENEFICIARY_REMOVED` audit via
  `audit.api` (default holds ONLY absent an incompatible retention policy;
  inspection confirms retention is OPEN in ADR-02/04/06/08/11 — no policy
  contradicts physical deletion). The removal audit records the event, not
  necessarily the full historic payload: audit-`details` content must be
  verified before treating removal traceability as complete. No beneficiary
  lifecycle states in v1.
- Packaging: `beneficiaries/internal` + `beneficiaries/web`, NO
  `beneficiaries/api` in v1 (no inter-module consumer; ADR-17). Allowed:
  `beneficiaries -> accounts.api`, `-> identity`, `-> audit.api`. The
  `financialops -> beneficiaries` edge MUST NOT exist in v1 (asserted
  negatively by ArchUnit). The Alternative B resolution contract is
  documented as future evolution only and is NOT created here.
- Audit: `BENEFICIARY_CREATED/REJECTED/CONFLICT/REMOVED` via `audit.api`
  only; history/audit/logs separation preserved (ADR-11). Evidence:
  `JpaAuditRecorder.record()` declares no transaction and joins the caller's
  `writeTx`; create/delete audit therefore commits or rolls back with the
  effect, and `CONFLICT` audit runs in its own `writeTx` (patterns
  `DepositService.attempt():214-226` + `conflict():270-280`). Full
  cross-engine atomicity is asserted by the required H2 + PostgreSQL
  atomicity tests, not by the recorder interface alone.
- Atomicity: each create commits (effect + idempotency where
  applicable + audit) in one local transaction: `CONFIRMED` = row +
  idempotency (id set) + audit; `REJECTED` = idempotency (id NULL) + audit
  with no row, returned normally inside the transaction and thrown only
  after commit (patterns `DepositService.attempt():176-185`,
  `TransferService.attempt():232-236`). Delete commits removal + audit in
  one local transaction. No distributed transaction.
  A failure before commit leaves no beneficiary effect; a committed create
  always carries its idempotency and audit evidence. `CONFLICT` persists
  audit only, in a separate transaction; `FAILED`/`UNKNOWN` persist nothing.

## Atomicity

A confirmed beneficiary creation MUST atomically coordinate, within the same
local database transaction: the beneficiary row, the creation-idempotency
record, and the audit event. A rejected creation persists its idempotency
and rejection audit with no beneficiary row. A delete MUST atomically
coordinate the row removal and the removal audit. There is no valid partial
beneficiary effect, and no money effect of any kind.

Validation strategy (design requirement): H2 unit, persistence (unique
arbiter), idempotency/conflict/recovery, authz + disclosure matrices, delete
semantics, and ArchUnit regression; the same proofs MUST run against real
PostgreSQL (`postgres:16-alpine`) in the `*Postgres*` gate, because
PostgreSQL aborts the whole transaction on constraint violation and has
distinct lock/visibility semantics.

## Consequences

- What becomes easier or more possible:
  - saved destinations without risking money invariants;
  - deterministic owner-scoped CRUD with safe disclosure;
  - a documented base a future beneficiary-transfer increment can reuse.
- What becomes harder or more difficult:
  - one additive Accounts read contract must be owned and maintained;
  - ArchUnit rules grow by one module;
  - alias-immutability and physical delete are v1 constraints callers must accept.
- Trade-offs:
  - Transfer-untouched safety now versus beneficiary-initiated transfer UX later;
  - no speculative `beneficiaries.api` now versus a small additive step when a
    real Transfer consumer is approved;
  - physical delete simplicity now versus logical-state machinery deferred
    until retention policy demands it.

## Alternatives Considered

- Beneficiary resolution inside Transfer in v1 -> Rejected: touches the most
  sensitive invariant without an approved consumer.
- `beneficiaries.api` resolution service now -> Rejected: speculative
  abstraction without a consumer (ADR-17).
- Operability (`ACTIVE`) gate at save -> Rejected as default: stale check
  cannot replace operation-time enforcement.
- `UNIQUE(owner, alias)` -> Rejected: unconfirmed need.
- Logical deletion with states -> Rejected as default: extra machinery
  without retention evidence.
- External destinations -> Rejected for v1: no rails/FX justification.
- Sagas, events, queues, distributed transactions, microservices -> Rejected
  per ADR-01/ADR-14/ADR-17.

Explicit non-decisions: exact Java class/method names beyond the directions
above, exact DDL beyond the conceptual V10, index selection beyond the owner
index direction, HTTP field names beyond the candidate shapes, retention/
cleanup policy, and recovery job implementation. Transfer representation,
lifecycle transitions, ledger evolution, observability tooling, PostgreSQL
validation work itself, and any distributed concern are explicitly out of
this ADR.

## Relationship to Existing ADRs

- ADR-01: new module inside allowed edges; no boundary change.
- ADR-05: honors Beneficiary-is-reference + traceability; leaves
  direct-vs-beneficiary-only open; no model change.
- ADR-06: same determinism in a new dedicated namespace; no principle change.
- ADR-09: one additive stub-shaped method; no provider change.
- ADR-10: versioned REST, queryable results, safe errors; no principle change.
- ADR-11: new transverse actions; separation preserved; no audit change.
- ADR-17: `internal` + `web` without ornamental `api/`; ArchUnit extended.
- ADR-18/19: money and lifecycle paths untouched.

No existing ADR is modified by this decision.

## Status

Accepted - formally approved by human review with P1-P8 and precisions A-C.

Implementation note (Phases 3): migration V10 now exists and creates the two tables described below; later phases added the ADR-21 read-only `beneficiaries.api` resolver and V11 transfer evidence. The conceptual/OPEN wording in this ADR is retained as design history, not as a claim that the schema is absent.

## Notes

- Compatible with ADR-01 through ADR-19 at creation time. Human-approved
  P1-P8 and precisions A-C are recorded in `docs/beneficiary-design.md`.
- Language: MUST for decided architectural rules; SHOULD for recommendations;
  MAY for future possibilities.
