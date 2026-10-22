# Feature Documentation: Beneficiaries (saved internal destinations, v1)

## Objective

First Beneficiaries capability of Banco El Capital: an authenticated customer saves,
lists, inspects, and removes references to internal bank accounts owned by any
customer, for later use as transfer destinations. Saving a beneficiary moves no
money; removing one deletes no account and reverses no transfer.

The original v1 design implemented Alternative A from the Architecture Discovery:
Beneficiaries manages saved destination references and Transfer initially kept
receiving account UUIDs directly. ADR-21 subsequently approved the explicit
beneficiary-resolution contract (the discovery's "Alternative B"), and Phases
4.1-4.3 implemented it. Transfer now accepts either a direct destination UUID or
a saved `beneficiaryId`, never both, and resolves the destination server-side
through `beneficiaries.api.BeneficiaryResolver.resolveForOwner`.

Baseline note: the statement below about `main` at `9a569a6` and an empty
`beneficiaries/` placeholder is historical. The module, `api/`, `internal/`,
`web/`, V10 and the ADR-21 consumer now exist.

## Implementation Status (Phases 3-4.3)

- V10 creates `beneficiaries` and `beneficiary_creation_idempotency`. V11 adds
  transfer-beneficiary intent evidence and `transfer_beneficiary_usage`.
- CRUD is implemented at the documented endpoints, owner-scoped under the stub.
- `beneficiaries.api.BeneficiaryResolver` is a read-only, owner-scoped contract
  used by `financialops.transfer`. It takes no row lock and exposes only a
  `BeneficiaryResolution` (id, alias, destination account, destination currency,
  owner customer id).
- `financialops.transfer -> beneficiaries.api` is the only allowed cross-module
  edge. `financialops -> beneficiaries.internal|web` remains prohibited.
- A transfer resolves the beneficiary in phase 1 for a fast denial and again in
  phase 2 inside `writeTx` before any financial effect. After both account locks
  are held it re-reads owner, destination and destination currency and rejects on
  a mismatch. Authoritative reads use projections, never managed entities, so a
  post-lock query cannot return stale first-level-cache state.
- Deleting a beneficiary does not delete or rewrite `transfer_beneficiary_usage`;
  the usage evidence is preserved without a physical FK.

## Scope

In scope: internal-account beneficiaries with create / list / get / delete,
owner-scoped authorization under the current stub, destination validation via
Accounts-owned contracts (existence + currency, no operability gate at save
time), safe errors without cross-customer disclosure, transverse audit evidence,
creation idempotency in a dedicated namespace, the V10 schema plus V11 transfer
usage evidence, the ADR-21 read-only resolver consumed by Transfer, and the
H2 + PostgreSQL + ArchUnit validation plan.

Out of scope: external bank destinations (ACH, cards, rails), FX, fees, limits,
overdraft, alias editing (alias immutable in v1), beneficiary lifecycle states
beyond existence/removal, logical deletion with states, pagination, `BANK_EMPLOYEE`
management flows, formal ledger / double-entry, event sourcing, sagas,
Kafka/Redis/microservices/distributed transactions, real authentication
(JWT/OAuth/Spring Security), retention/cleanup policy beyond the documented
OPEN status. Transfer's beneficiary execution is no longer out of scope: it is
implemented under ADR-21 and documented in `transfer-design.md`.

## Actors and Ownership

- Actor: authenticated actor under the current stub (`X-Actor-Id` header,
  `AuthenticatedActor(subject, roles)`). Controllers build it with
  `Set.of()` today (`TransferController:53`, `DepositController:45`), so the
  `BANK_EMPLOYEE` path is unreachable via REST; v1 default is self-only.
- Owner: `owner = actor.subject()`, always server-resolved. The request body
  carries NO owner/customer identity (same rule Transfer applies to holders:
  "client supplies only account UUIDs, never holders" —
  `TransferService.transfer():112-115`).
- Authorization: new `AuthorizationService.decideManageBeneficiary(actor,
  ownerCustomerId)` mirroring `StubAuthorizationService.decide()` semantics
  (`subject==owner || BANK_EMPLOYEE`), exercised as self-only in v1.
- Owner existence is checked via the existing `IdentityGateway.customerExists()`
  (`JpaIdentityGateway:21-26`); Beneficiaries MUST NOT access
  `CustomerRepository` directly.

## Preconditions and Business Rules

Create confirms only when all hold:

- `Idempotency-Key` present and non-blank (missing key is 400 input error,
  never a technical failure — same convention as `TransitionController:82-84`).
  Missing/blank key persists nothing.
- Actor authenticated and authorized for the server-resolved owner BEFORE any
  idempotency read or write. `UNAUTHENTICATED`/`FORBIDDEN` throw immediately
  and persist no idempotency row and no audit event — same ordering as
  `AccountCreationService.create():62-65`, `DepositService.deposit():98-101`,
  `TransferService.transfer():131-134`, `AccountTransitionService:82-85`.
- `destinationAccountId` present; `alias` normalized as defined in
  "Alias normalization" below (trimmed, 1..64 after trim, no control
  characters; alias immutable after creation in v1).
- Destination exists per the Accounts-owned read contract (see below);
  unknown destination is a deterministic business rejection PERSISTED as a
  `REJECTED` idempotency record with `beneficiary_id NULL` (no beneficiary
  row), following `AccountCreationService.attempt():122-134` (REJECTED with
  null reference + rejection audit) and `DepositService.reject():234-262` /
  `TransferService.reject():338-366` (REJECTED operation/idempotency + audit,
  returned normally so the write transaction commits instead of rolling the
  rejection back).
- Destination currency is an explicit ISO code (`^[A-Z]{3}$`, same constraint
  shape as `CreateTransferRequest:13` / `CreateAccountRequest:8`); the stored
  snapshot is informational only.
- No duplicate `(owner_customer_id, destination_account_id)` for the same
  owner (DB unique constraint is the arbiter; concurrent winners converge via
  reload + replay, same pattern as `AccountCreationService:73-84`).
- Alias duplicates across different destinations of the same owner are
  ALLOWED in v1: no `UNIQUE(owner, alias)` (human-approved precision C).

Operability precision (human-approved precision A):

- Saving does NOT require the destination to be `ACTIVE`. `BLOCKED`/`CLOSED`
  destinations may be saved; operability (`Account.isOperable() == ACTIVE`,
  `Account.java:102-104`) is evaluated at financial-operation time under the
  account row write lock (`findByIdForUpdate`), exactly as Deposit/Withdrawal/
  Transfer do today (`AccountCreditService:38-40`,
  `AccountDebitService:38-40`, `TransferService.reasonForDebit/
  reasonForCredit`). A stale validation at save time MUST NOT substitute for
  the Transfer-time check. Rejecting blocked destinations at save time would
  require an explicit product-rule approval and is NOT the default.

Delete rules:

- Delete is physical removal of the beneficiary row plus a `BENEFICIARY_REMOVED`
  audit event (human-approved precision B default; precision C conditioned:
  physical remains the default ONLY because repository inspection found no
  incompatible retention policy — retention is explicitly OPEN in ADR-02,
  ADR-04, ADR-06, ADR-08, ADR-11 and transfer-design P5, and the only
  `delete from audit_events` statements in the repo are test-scoped cleanups).
  The `BENEFICIARY_REMOVED` audit records that a removal happened; it does NOT
  by itself preserve the full historic beneficiary payload, so traceability
  completeness depends on what the audit `details` column actually stores
  (human-approved precision C: verify audit payload before treating removal
  traceability as satisfied). No logical-deletion states in v1; logical
  deletion stays a documented alternative if a future retention policy
  requires row preservation (it would add states and rules).
- Delete is naturally idempotent per id: second delete of the same id returns
  404. No idempotency-key namespace for delete in v1.
- Delete has no effect on accounts, movements, operations, or idempotency
  records of any other namespace.

## Design

- Solution approach: `beneficiaries` feature with `internal/` (entity,
  repositories, service, idempotency record), `web/` (controllers, DTOs,
  exception handler) and `api/` (read-only resolver contract). The original v1
  deliberately omitted `api/` because Transfer did not consume Beneficiaries;
  ADR-21 created that consumer and Phase 4 added `api/`. Allowed dependencies:
  `beneficiaries -> accounts.api`, `beneficiaries -> identity`,
  `beneficiaries -> audit.api`, plus the separately approved
  `financialops.transfer -> beneficiaries.api` edge. Prohibited:
  `beneficiaries -> accounts.internal|web`, `-> customers.*`,
  `-> financialops.*`, `-> audit.internal`, `financialops -> beneficiaries.internal|web`,
  and any `web -> Repository` or `web -> Entity` (same Rules A-E as ADR-17;
  ArchUnit rules updated, not weakened).
- Transactional strategy (PostgreSQL-safe, verified pattern): the create
  attempt runs in exactly one outer local write transaction (`writeTx` via
  `TransactionTemplate`), and the winner reload after a
  `DataIntegrityViolationException` or post-write duplicate detection runs in
  a SEPARATE fresh read transaction (`readTx`, read-only) against committed
  state. This is the `DepositService:37-41,112-130`,
  `TransferService:49-54,172-194`, and `AccountTransitionService:31-35,97-114`
  pattern, required because PostgreSQL aborts the whole transaction on any
  constraint violation, so a reload inside the same transaction after the
  violation could never succeed. The older single-`@Transactional`
  catch-and-reload shape (`AccountCreationService.create():72-84`) MUST NOT be
  copied. An explicit flush inside `writeTx` surfaces uniqueness violations
  deterministically before commit (pattern
  `DepositService.attempt():227-230`, `TransferService.attempt():331-335`).
  Beneficiary creation holds no account row lock (single-row insert; the
  `UNIQUE(owner, destination)` constraint is the concurrency arbiter), so
  there is no UUID-ordered two-row locking.
- Concurrency cases:
  - Same key + same payload (hash equal): exactly one beneficiary effect; the
    loser reloads the committed winner in `readTx` and replays it
    (`created=false`); if no winner is committed yet the caller gets `UNKNOWN`
    and retries with the SAME key (patterns
    `DepositService:118-130`, `TransferService:177-189`).
  - Same key + different payload (hash mismatch): `CONFLICT`; the conflicting
    audit is recorded in its own write transaction and the conflict is thrown
    only afterwards, leaving idempotency state untouched (pattern
    `DepositService.conflict():264-280`, `TransferService.conflict():368-389`,
    documented "never called from inside a read-only transaction").
  - Duplicate `(owner, destination)` with DIFFERENT keys: each key is a
    distinct intent (no business-key reuse, same money-grade rule as
    deposits/transfers); the second key deterministically persists its own
    `REJECTED` record ("duplicate destination") with no beneficiary row.
- Create path: authorize (throw, no persist on deny) -> compute canonical hash
  -> check existing key OUTSIDE `writeTx` (replay/conflict fast path, patterns
  `DepositService:103-109`, `TransferService:136-142`) -> `writeTx.execute`
  attempt (validate incl. unknown destination as persisted REJECTED; insert
  beneficiary + idempotency + audit; flush) -> on
  `DataIntegrityViolationException` reload winner in `readTx` and
  replay/conflict/`UNKNOWN`.
- Destination validation contract (new, minimal, Accounts-owned): the existing
  `AccountLookupService.findHolderCustomerId()` is insufficient (exposes holder,
  gives no currency). v1 needs a read-only contract such as
  `AccountDestinationLookup.findDestination(UUID) -> Optional<DestinationCurrency>`
  where presence means the destination exists (the currency rides inside the
  present value) and absence means it does not — so no incoherent
  `exists=false`-with-currency state is representable (human-approved precision
  A). The payload carries currency only — no holder, no status, no balance.
  Exact name/shape is implementation detail inside ADR-20 bounds; it MUST live
  in `accounts.api` and MUST NOT expose `accounts.internal`.
- Read paths are owner-scoped: list returns only rows where
  `owner_customer_id == actor.subject()`, ordered by `created_at`; get by id
  returns the row only if owned.
- Affected components: `beneficiaries` (new), `accounts.api` (additive read
  contracts for destination currency), `identity` (additive
  `decideManageBeneficiary` method + stub), `audit` (new `BENEFICIARY_*`
  actions via existing `audit.api`), and the ADR-21 `financialops.transfer`
  consumer through `beneficiaries.api` only.
- Data changes: V10 `beneficiaries` + `beneficiary_creation_idempotency`; V11
  adds transfer-beneficiary intent columns and `transfer_beneficiary_usage`.
  Cross-module references stay logical (no physical FK to accounts/customers),
  consistent with V2/V4/V5/V8/V9.
- Backward compatibility: additive tables/endpoints. Existing direct transfers
  keep their request shape and hash identity; the beneficiary path is an
  explicit XOR alternative.

## Outcome Semantics

Persisted creation outcomes in `beneficiary_creation_idempotency` (reconciled
with Atomicity below):

- `CONFIRMED`: committed beneficiary row + idempotency record with
  `beneficiary_id` set + `BENEFICIARY_CREATED` audit, all in one `writeTx`.
- `REJECTED`: NO beneficiary row; idempotency record with
  `beneficiary_id NULL` + `BENEFICIARY_REJECTED` audit, committed in one
  `writeTx`. Covers unknown destination, invalid alias, duplicate
  owner+destination (including the different-keys duplicate case), and any
  other deterministic business refusal. The service method returns the
  rejection normally inside the transaction and throws the `REJECTED`
  exception only after commit (patterns `DepositService.attempt():176-185`
  javadoc "returns normally (never throws business outcomes) so REJECTED
  records actually commit" and `TransferService.attempt():232-236`), so the
  rejection evidence is never rolled back with the transaction that wrote it.
- Never persisted: `UNAUTHENTICATED`/`FORBIDDEN` (thrown before any
  idempotency read/write, no audit), missing/blank `Idempotency-Key` (400
  input error), `FAILED`/`UNKNOWN` (caller-side/technical, never rows).
  `CONFLICT` writes only a `BENEFICIARY_CONFLICT` audit in its own write
  transaction and leaves idempotency state untouched.

Replay behavior: same key + same hash with stored `CONFIRMED` returns the
stored beneficiary with `created=false` (201 first time, 200 replay — same
convention as `AccountController:46-48`, `DepositController:46-48`); same
key + same hash with stored `REJECTED` rethrows `REJECTED` ("request
previously rejected", patterns `AccountCreationService.replay():166-171`,
`TransferService.replay():391-397`).

- CONFLICT: same key + incompatible payload; conflicting audit recorded in its
  own write transaction without mutating idempotency state (pattern
  `TransferService.conflict():373-389`); no beneficiary row from the loser.
- FAILED: determined technical failure with no effect (e.g. hashing failure,
  persistence failure with no winner found in `readTx`); never inferred from
  a lost response.
- UNKNOWN: caller-side uncertainty only (e.g. uniqueness race with no
  committed winner yet); recovery always via the original
  `Idempotency-Key` (retry or outcome query), never via a new key.

## API Direction (contracts; finalized in implementation within these bounds)

DTO shapes mirror existing records (`TransferResponse(operationId, created)`,
`CreateTransferRequest` with `@NotNull`/`@Pattern` bean validation):

- `POST /api/v1/beneficiaries`, headers `Idempotency-Key` (required),
  `X-Actor-Id` (required stub identity); body
  `{ destinationAccountId: UUID (required), alias: string (required) }`;
  - `201 { beneficiaryId: UUID, created: true }` first confirmation;
  - `200 { beneficiaryId: UUID, created: false }` same-key + same-hash replay.
- `GET /api/v1/beneficiaries`, header `X-Actor-Id`; response
  `[{ beneficiaryId, alias, destinationAccountId, destinationCurrency,
  createdAt }]` owner-only, ordered by `createdAt`; no filters in v1.
- `GET /api/v1/beneficiaries/{id}`, header `X-Actor-Id`; response
  `{ beneficiaryId, alias, destinationAccountId, destinationCurrency,
  createdAt }`; missing row OR another owner's row returns identical `404`
  with no distinguishing detail.
- `DELETE /api/v1/beneficiaries/{id}`, header `X-Actor-Id`; `204 No Content`
  with empty body on removal (human-approved precision B; no response body
  needed for a successful delete). No idempotency key for delete in v1.
  Unknown-or-foreign id returns identical `404`; second delete of the same id
  returns `404`.
- `GET /api/v1/beneficiary-operations/{key}`, headers
  `Idempotency-Key-Hash` (required) + `X-Actor-Id`, for timeout/disconnect
  recovery (same shape as `TransferController.findByKey:59-74`); response
  `{ beneficiaryId: UUID | null, outcome: "CONFIRMED" | "REJECTED" }`
  (`beneficiaryId null` when the stored outcome is `REJECTED`); authorized by
  owner; hash mismatch and absence both resolve to `404` without
  distinguishing.
- Implemented Alternative B (ADR-21): `POST /api/v1/transfers` accepts exactly
  one of `beneficiaryId` and `destinationAccountId`, resolved server-side. A
  transfer by beneficiary persists enough evidence to reconstruct which
  beneficiary was used: `transfer_operation_idempotency` intent columns for
  CONFIRMED/REJECTED (including the V13 direct-destination evidence on the
  direct path) and `transfer_beneficiary_usage` for CONFIRMED usage (no
  physical FK; evidence survives beneficiary deletion).

## Safe Errors (no disclosure)

Exception-handler mapping mirrors `TransferExceptionHandler:23-30` and
`DepositExceptionHandler:22-29` (`{ "error": message }` body):
`UNAUTHENTICATED -> 401`, `FORBIDDEN -> 403`, `REJECTED -> 422`,
`CONFLICT -> 409`, `FAILED/UNKNOWN -> 500` with a same-key recovery message
(never the internal reason). Missing/blank `Idempotency-Key` on create and
missing `Idempotency-Key-Hash` on recovery are `400` input errors (patterns
`TransitionController:82-84`, `TransferController:62-64`). Full matrix:

| Case | HTTP | Body / effect |
|---|---|---|
| First confirmation | 201 | `{ beneficiaryId, created: true }` |
| Same key + same hash (`CONFIRMED`) | 200 | `{ beneficiaryId, created: false }` |
| Same key + same hash (stored `REJECTED`) | 422 | `{ "error": "request previously rejected" }`, no new row |
| Same key + different payload | 409 | `{ "error": "idempotency key already used with incompatible payload" }` + `BENEFICIARY_CONFLICT` audit; idempotency untouched |
| Unknown destination / invalid alias / duplicate owner+destination | 422 | generic reason (`"unknown destination"`, alias rule, `"duplicate destination"`); persisted as `REJECTED` under the request key (except validation-shape 400s below) |
| Malformed body (null destination, blank alias) | 400 | bean-validation error; persists nothing |
| Missing/blank `Idempotency-Key` (create) or missing hash (recovery) | 400 | persists nothing |
| Unauthenticated (blank actor) | 401 | persists nothing, no audit |
| Cross-owner write (decided deny) | 403 | persists nothing, no audit |
| `GET` by id missing OR foreign | 404 | identical empty `404`, no detail |
| Recovery hash mismatch OR absent key | 404 | identical empty `404`, no detail |
| Determined technical failure, no effect | 500 | same-key recovery message; persists nothing unless a `REJECTED` was already committed |
| Uniqueness race, no committed winner | 500 (`UNKNOWN`) | `"uncertain outcome, retry with the same idempotency key"`; persists nothing |

`GET`/`DELETE` responses never include destination-holder identity, balance,
or status; `422` reasons never name holders. Authorization denials (401/403)
persist nothing and reveal nothing about beneficiary existence.

## Identity Stub Limits (X-Actor-Id is NOT production auth)

The `X-Actor-Id` header plus `new AuthenticatedActor(actorId, Set.of())`
(construction site in every controller, e.g. `TransferController:53`,
`DepositController:45`, `AccountController:45`, `TransitionController:65,89`)
is a development stub per ADR-09: the header value alone is self-asserted,
carries no signature, token, or proof of possession, and is trivially
spoofable by any client. It MUST NOT be described as authentication. The v1
"self-only" rule (`actor.subject().equals(owner)`, cf.
`StubAuthorizationService.decide():52-60`) therefore means "the caller
naming an owner" rather than a verified identity; it is accepted for MVP
only because all beneficiary data is owner-scoped, bearer-equivalent, and
disclosure-safe by construction (404 equivalence, minimal projection). No
new roles, no employee flows, and no trust in client-supplied owner ids are
introduced in v1; a real provider remains an explicit non-goal.

## Alias Normalization and Request Hashing

Normalization (single source of truth, applied before validation,
persistence, and hashing):

1. Require non-null; trim leading/trailing whitespace (including
   surrounding spaces/tabs); reject if empty after trim.
2. Reject control characters (`Character.isISOControl`); keep inner spacing
   and case exactly as trimmed (no collapsing, no case folding).
3. Enforce length 1..64 AFTER trim; persist the trimmed verbatim form
   (case preserved) in `beneficiaries.alias` (`VARCHAR(64)`).

Canonical hash input (exact order, `|` separator, no whitespace, UTF-8,
SHA-256 lowercase hex 64 — same construction as
`TransferService.requestHash():432-449` and
`AccountCreationService.requestHash():196-207`):

`owner-lowercase | destination-uuid-lowercase | alias-trimmed-verbatim`

where owner is `actor.subject()` lowercased (`Locale.ROOT`), destination is
`destinationAccountId.toString()` lowercased, and alias is the NORMALIZED
(trimmed, case-preserved) value — never the raw body string. Rationale: two
requests differing only in surrounding whitespace address the same stored
alias and MUST replay; two aliases differing in case are distinct persisted
values (no alias uniqueness) and MUST hash distinctly, so the alias leg is
NOT lowercased. Replay comparison is exact string equality against the
stored hash.

## Audit and Idempotency

- Audit via `audit.api.AuditRecorder.record(AuditEntry)` only; never
  `audit.internal`. Actions: `BENEFICIARY_CREATED`, `BENEFICIARY_REJECTED`,
  `BENEFICIARY_CONFLICT`, `BENEFICIARY_REMOVED`; `resourceType="Beneficiary"`;
  `resourceId` = beneficiary UUID, or the idempotency key for rejections and
  conflicts (patterns `AccountCreationService:125-132`,
  `TransferService:357-364`, `DepositService:254-260`).
- Transactional guarantee (repository evidence): `JpaAuditRecorder.record()`
  (`audit/internal/JpaAuditRecorder.java:18-20`) declares NO transaction
  annotation and simply calls `AuditEventRepository.save()`; the audit insert
  therefore joins the CALLER's local transaction (REQUIRED propagation of the
  Spring Data call). All money and lifecycle predecessors invoke it inside
  the same `writeTx.execute` that writes effects + idempotency
  (`DepositService.attempt():214-226`, `TransferService.attempt():316-330`,
  `AccountTransitionService` same shape), so CONFIRMED/REJECTED audit commits
  or rolls back with the effect; `CONFLICT` audit runs in its OWN `writeTx`
  (`DepositService.conflict():270-280`, `TransferService.conflict():373-389`)
  so the evidence survives. Beneficiaries v1 MUST follow exactly this: create
  and delete audit inside the same `writeTx` as the effect (+ idempotency for
  create); conflict audit in its own `writeTx`. The required atomicity tests
  (failure injection asserting zero beneficiary row, zero idempotency row,
  and zero CONFIRMED audit with balances/state intact, on H2 AND real
  PostgreSQL — mirroring `DepositAtomicityTest` / `TransferAtomicityTest` /
  `*PostgresRollbackTest`) are the proof; full cross-engine atomicity is
  asserted by tests, not by the `AuditRecorder` interface alone.

## Atomicity (reconciled with ADR-20)

A confirmed creation commits beneficiary row + idempotency `CONFIRMED` row +
`BENEFICIARY_CREATED` audit in ONE `writeTx`; a deterministic rejection
commits idempotency `REJECTED` row (`beneficiary_id NULL`) +
`BENEFICIARY_REJECTED` audit in ONE `writeTx` with no beneficiary row; a
delete commits row removal + `BENEFICIARY_REMOVED` audit in ONE `writeTx`.
Business rejections return normally inside the transaction and throw only
after commit. No `FAILED`/`UNKNOWN`/`CONFLICT` state is ever persisted as an
idempotency outcome; `CONFLICT` persists only its audit in a separate
`writeTx`. This matches the persisted-outcome table in Outcome Semantics and
the ADR-20 Atomicity section verbatim in requirements.
- Creation idempotency in a dedicated namespace
  (`beneficiary_creation_idempotency`), separate from account/customer/
  deposit/withdrawal/transfer/transition namespaces (ADR-06). Canonical hash
  input (exact order, `|` separator, no whitespace):
  `owner-lower|destination-uuid-lower|alias`, UTF-8 bytes, SHA-256 hex
  lowercase (64 chars). Same intent + same key MUST NOT create a second row;
  same key + incompatible payload is a conflict.

## Data — V10 implemented (V11 adds transfer evidence)

```sql
-- V10__beneficiary_foundation.sql (implemented; exact production DDL may differ)
CREATE TABLE beneficiaries (
  id UUID PRIMARY KEY,
  owner_customer_id VARCHAR(64) NOT NULL,
  destination_account_id UUID NOT NULL,
  alias VARCHAR(64) NOT NULL,
  destination_currency VARCHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_beneficiary_owner_destination UNIQUE (owner_customer_id, destination_account_id)
  -- no UNIQUE(owner, alias); no FK to accounts/customers (logical refs, per V2/V4/V5/V8/V9)
);
CREATE INDEX ix_beneficiaries_owner ON beneficiaries(owner_customer_id);

CREATE TABLE beneficiary_creation_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  beneficiary_id UUID NULL, -- logical, no REFERENCES (allows REJECTED rows; delete-safe)
  request_hash VARCHAR(64) NOT NULL, -- VARCHAR directly per V7 fix
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## Testing Strategy (design requirement)

H2: service unit (validation matrix incl. BLOCKED-savable), persistence
(unique arbiter), idempotency/conflict/recovery (same-key convergence,
hash mismatch), authz matrix (cross-owner create/list/get/delete),
disclosure matrix (404 equivalence, no holder/balance leakage), delete
semantics (physical + audit, second delete 404).

PostgreSQL gate (`postgres:16-alpine`, Testcontainers,
`postgres/AbstractPostgresTest` pattern): same schema, idempotency,
concurrency, and audit proofs MUST run against real PostgreSQL (PG aborts
the whole transaction on constraint violation; distinct lock semantics —
same rationale as ADR-18/19).

ArchUnit: new rules `beneficiariesMustNotDependOn{AccountsInternal,
AccountsWeb, Customers, FinancialOps, AuditInternal}`,
`beneficiariesWebMustNotDependOnRepositories/Entities`,
`moduleApisMustNotDependOnWeb` coverage, `moduleSlicesAreFreeOfCycles`, and the
ADR-21 exception allowing only `financialops.transfer -> beneficiaries.api`.
`financialops -> beneficiaries.internal|web` MUST remain absent (asserted
negatively).

## Decisions Recorded (human-approved)

P1 internal-only scope; P2 original v1 transfer unchanged (superseded by the
separately accepted ADR-21 for the beneficiary path); P3 CRUD
create/list/get/delete; P4 alias immutable v1; P5 uniqueness on
`(owner, destination)` only; P6 self-only authz; P7 minimal disclosure
(alias + opaque id + currency); P8 logical references without physical FKs;
precision A (no ACTIVE gate at save); precision B (physical delete + audit,
no delete idempotency namespace); precision C (alias duplicates allowed).

## Rejected Alternatives (with reason)

- Beneficiary resolution inside Transfer in the original v1 -> Rejected then
  because no approved consumer contract existed. ADR-21 later approved that
  contract and Phase 4 implemented it; this entry is historical, not a current
  prohibition.
- `beneficiaries.api` with resolution service in the original v1 -> Rejected
  then as speculative because no consumer existed. ADR-21 created the consumer
  and the contract is now implemented.
- Operability gate at save -> Rejected as default: stale check cannot replace
  the Transfer-time lock-protected check; only a product rule could justify it.
- `UNIQUE(owner, alias)` -> Rejected: unconfirmed need; would forbid equal
  aliases the product may allow.
- Logical deletion with states in v1 -> Rejected as default: extra states and
  rules without retention-policy evidence; physical + audit preserves
  traceability with less machinery.
- External destinations -> Rejected for v1: no rails/FX justification
  (ADR-01/ADR-05/ADR-14).
- Sagas, events, queues, distributed transactions, microservices -> Rejected
  per ADR-01/ADR-14/ADR-17: local transactions suffice.

## Relationship to Existing ADRs

- ADR-01: new module inside allowed edges; no boundary change.
- ADR-05: honors "Beneficiary is NOT Account" + traceability requirement;
  leaves direct-vs-beneficiary-only open; no model change.
- ADR-06: same logical-identity determinism in a new dedicated namespace.
- ADR-09: stub authz extended by one method, same self-or-employee shape.
- ADR-10: versioned REST, queryable results, safe errors preserved.
- ADR-11: transverse audit with new actions; history/audit/logs separation kept.
- ADR-17: feature packaging (`api` + `internal` + `web`); the `api/` contract
  now exists for the ADR-21 consumer. ArchUnit extended, not weakened.
- ADR-18/19: money and lifecycle paths changed only through the separately
  accepted ADR-21 beneficiary path; operability stays observed at operation
  time under the account locks.
- ADR-21: accepted and implemented; see `transfer-design.md` and
  `ADR-21-transfer-via-saved-beneficiary.md` for the two-phase authorization,
  source `403` rule, post-lock revalidation and V11/V12 evidence.

## Status

Accepted design — formally approved by human review with P1-P8 + precisions A-C
(A: `Optional<DestinationCurrency>` minimal contract; B: `204 No Content` on
delete; C: physical delete default, retention OPEN per repo inspection).
Implementation of the original v1 stayed within those bounds. The later
ADR-21 beneficiary-transfer consumer, `beneficiaries.api`, Transfer changes and
V11 evidence were approved separately and are implemented in Phases 4.1-4.3;
they are not a silent deviation from the original v1 scope.
