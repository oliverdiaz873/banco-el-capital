# Implementation Plan: Account Movement History (read-only)

## Status

EXECUTED AND CLOSED (2026-10-18). This document is now a record of execution and
closure, not a plan. The implementation was separately authorized, delivered and
merged: PR #12 (`feat(financialops): add account movement history`), feature
commit `646dec8cac34947164f9078ac389557ff0a51df4`, merge commit
`19555bae91a86796792b1fa7671be1977a613e2f` on `main`.

The constraints, non-goals, allowed-file list and stop conditions below are
preserved as the record of the approved scope; they remain binding for any
future change to this capability.

It plans the implementation of the contract approved in ADR-22
(`docs/adr/ADR-22-account-movement-history.md`) and
`docs/movement-history-design.md`. The contract, its field set and its documented
limitations are treated as fixed; this plan does not reopen them.

## Objective

Implement, behind explicit later approval, the read-only endpoint
`GET /api/v1/accounts/{accountId}/movements` exactly as specified in ADR-22:
confirmed effects of one account, newest first, keyset-paginated, with the
documented privacy, precision and error guarantees.

## Non-goals (out of scope for this increment)

- Any write, balance change, new financial operation or new persistence entity.
- Any migration, including `V12`, and any index creation (ADR-22, "Deferred Index").
- Read auditing, retention, exports, filters by type/date/amount.
- Beneficiary alias or any counterparty identifier.
- Any change to existing endpoints, existing error codes, or other modules.
- Snapshot isolation, locks, or an "as-of" timestamp.

## Constraints (binding)

- Work happens only in the isolated documentation worktree
  (`banco-design-history`, branch `design/account-history`). The original working
  repository (the primary checkout of this project, on the developer's desktop)
  and the preservation worktree `banco-main-synced` MUST NOT be touched.
- No commits, pushes or PRs unless separately authorized.
- Module boundaries (ADR-01/ADR-17, enforced by `ModuleBoundariesTest`):
  the read lives in Financial Operations and depends only on `accounts.api` and
  `identity`; it MUST NOT reach `accounts.internal`, `accounts.web`, `customers`
  or `beneficiaries`. The web layer MUST NOT depend on repositories or on JPA
  entities (`Movement`, `FinancialOperation`).
- The existing account read `GET /api/v1/accounts/{id}` and its `403` mapping are
  NOT changed. This endpoint intentionally collapses the authenticated non-owner
  case to `404`.
- Money stays in integer minor units; `currency` is explicit; no `float`/rounding.

## Target module layout and ALLOWED FILES

New production files (allowed):

- `src/main/java/com/bancoelcapital/financialops/history/MovementHistoryService.java`
- `src/main/java/com/bancoelcapital/financialops/history/MovementHistoryRepository.java`
- `src/main/java/com/bancoelcapital/financialops/history/MovementHistoryEntry.java`
- `src/main/java/com/bancoelcapital/financialops/history/MovementHistoryPage.java`
- `src/main/java/com/bancoelcapital/financialops/history/MovementCursorCodec.java`
- `src/main/java/com/bancoelcapital/financialops/history/MovementHistoryException.java`
- `src/main/java/com/bancoelcapital/financialops/history/web/MovementHistoryController.java`
- `src/main/java/com/bancoelcapital/financialops/history/web/MovementHistoryExceptionHandler.java`
- `src/main/java/com/bancoelcapital/financialops/history/web/MovementHistoryResponse.java`

New test files (allowed):

- `src/test/java/com/bancoelcapital/financialops/history/MovementCursorCodecTest.java`
- `src/test/java/com/bancoelcapital/financialops/history/MovementHistoryServiceTest.java`
- `src/test/java/com/bancoelcapital/financialops/history/web/MovementHistoryControllerTest.java`
- `src/test/java/com/bancoelcapital/financialops/history/MovementHistoryIntegrationTest.java`
- `src/test/java/com/bancoelcapital/financialops/history/MovementHistoryPostgresTest.java`

Modified test file (allowed, additive only):

- `src/test/java/com/bancoelcapital/architecture/ModuleBoundariesTest.java`
  (add the rules listed under "Architecture tests").

Explicitly NOT allowed without a new approval: any file under
`src/main/resources/db/migration/`, `pom.xml`, `application*.yml`, workflow
files, `accounts.*`, `identity.*`, `customers.*`, `beneficiaries.*`, and any
existing controller or service used by the current read/write paths. Adding the
keyset query to the existing `financialops.core.MovementRepository` is
deliberately avoided to keep the write-path repository untouched; a dedicated
read repository is used instead.

## Behavioral contract (fixed)

- Path: `GET /api/v1/accounts/{accountId}/movements?limit=<n>&cursor=<opaque>`.
- Actor: `X-Actor-Id` header read directly (no authentication filter), as today.
- Item fields (exactly): `movementId`, `operationId`, `operationType`
  (`DEPOSIT`|`WITHDRAWAL`|`TRANSFER`), `direction` (`DEBIT`|`CREDIT`),
  `amountMinorUnits`, `currency`, `createdAt`, `counterparty`.
- Ordering: `created_at DESC, id DESC`.
- `limit`: default `20`, maximum `100`; out of range or non-numeric -> `400`.
- Cursor: opaque Base64URL; malformed/incoherent -> `400`.
- `counterparty`: `null` for `DEPOSIT`/`WITHDRAWAL`; `{ "type": "TRANSFER" }` for
  transfers. No third-party account id and no PII, ever.

## Query design (keyset)

- Source: `movements` filtered by `account_id = :accountId`; `operationType` is
  obtained by joining `financial_operations` on `movements.operation_id`
  (movements carry no type). The query selects ONLY the requested account's
  movements; it MUST NOT read, select or reconstruct the sibling leg's
  `account_id`.
- Predicate (portable explicit form, avoiding engine-specific row-value syntax):
  `(createdAt < :cursorCreatedAt) OR (createdAt = :cursorCreatedAt AND id < :cursorId)`.
  Ordered by `createdAt DESC, id DESC`, fetch `limit + 1` rows to decide whether a
  `nextCursor` exists.
- The projection is a flat record (`MovementHistoryEntry`); the service owns the
  mapping to `MovementHistoryPage` and to the response records. The web layer
  never sees `Movement` or `FinancialOperation`.
- Read runs in a `@Transactional(readOnly = true)` method; no lock; no mutation.

## Cursor precision design

- Encode: `micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000)`
  and the movement UUID, serialized as Base64URL of `"<micros>:<uuid>"`.
- Decode: validate segment count, integer range and UUID syntax FIRST; rebuild the
  instant as `Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L)`.
- `java.time.Instant` carries nanoseconds; PostgreSQL `TIMESTAMPTZ` stores
  microseconds, and the domain sets `createdAt` via `Instant.now()`. The codec MUST
  round-trip every microsecond value exactly (nano remainder is truncated to whole
  microseconds, never rounded up). Exact precision across PostgreSQL <-> JDBC <->
  Java is an acceptance requirement (ADR-22).

Validation is strict and fail-closed. The canonical payload is
`<micros>:<lowercase-uuid>`, where `<micros>` is a canonical signed 64-bit decimal
(no leading zeros, no `+` sign) and `<lowercase-uuid>` is the canonical
8-4-4-4-12 form. From that format a canonical unpadded Base64URL token is between
51 and 76 characters: the decoder MUST reject any token longer than 76 characters
before decoding it, reject empty or padded tokens, reject characters outside the
Base64URL alphabet, and reject non-canonical inputs by re-encoding the decoded
payload and requiring exact string equality with the input. Payloads that do not
match the canonical format (wrong separator count, wrong UUID shape or length,
non-canonical integer spelling such as leading zeros) are rejected. Every
rejection maps to `400` with a safe fixed message, never leaking an exception,
stack trace or internal detail. The encoding is a transport convenience only: it
is NOT a signature, NOT encryption and NOT proof of authorization.

## Authorization and error design

Order mirrors the existing account read (`AccountTransitionService.findAccountView`):
existence first, then the decision.

1. `AccountLookupService.findHolderCustomerId(accountId)` returns empty -> `NOT_FOUND`.
2. `AuthorizationService.decideViewAccount(actor, holder)`:
   - denied because the actor is unauthenticated (reason contains
     `"unauthenticated"`, same signal the existing code uses) -> `UNAUTHENTICATED`;
   - denied for any other reason (not holder, not `BANK_EMPLOYEE`) -> collapsed to
     `NOT_FOUND` (NOT `FORBIDDEN`).

HTTP mapping (`MovementHistoryExceptionHandler`):

- `UNAUTHENTICATED` -> `401` with a fixed safe body.
- `NOT_FOUND` -> `404` with a single, constant body used for BOTH the unknown
  account and the authenticated-but-unauthorized account, so the two responses
  are byte-for-byte identical.
- Validation errors (`limit`, `cursor`) -> `400`.
- Unexpected failure -> `500` with a safe body; never internals, balances,
  stack traces or third-party identifiers.

## Mandatory tests

Cursor codec (`MovementCursorCodecTest`):

- Round-trip exactness for a range of microsecond values, including ties and
  pre-1970/negative epochs.
- Rejects empty, padded, over-long (above the 76-character maximum), non-Base64,
  structurally incorrect and non-canonical tokens (see "Cursor precision design"),
  each mapped to `400` without leaking internals.
- Confirms the token is NOT signed/encrypted (no secret required) and carries no
  PII.

Service (`MovementHistoryServiceTest`, repositories/contracts mocked):

- Keyset predicate and ordering `created_at DESC, id DESC`; `limit + 1` fetch;
  `nextCursor` present/absent and `null` at the end.
- `counterparty` is `null` for deposit/withdrawal and `{type:TRANSFER}` for
  transfers; no field ever contains a third-party `accountId` or PII.
- Unknown account -> `NOT_FOUND`; authenticated non-owner -> `NOT_FOUND`
  (never `FORBIDDEN`); unauthenticated -> `UNAUTHENTICATED`.
- Read-only: no `save`/`delete`/`saveAll` interaction is ever recorded on any
  repository; no audit call.

Web slice (`MovementHistoryControllerTest`, `@WebMvcTest`, service mocked):

- `200` with the documented JSON shape and empty-page case.
- `400` for `limit` out of range/non-numeric and for a malformed `cursor`.
- `401` for a missing/blank actor on an existing account.
- `404` for unknown and for authenticated non-owner at the HTTP mapping layer.
  NOTE: with a mocked service this checks only the mapping, NOT the real
  authorization path; the byte-level equality is enforced by the integration test
  below.

Integration (real path) (`MovementHistoryIntegrationTest`, real beans, no mocked
history service):

- Boots the real Spring context the way the existing real-integration tests do
  (for example `TransferReplayAuthorizationTest`): `@SpringBootTest(properties = {`
  `"spring.datasource.url=jdbc:h2:mem:movement-history-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",`
  `"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",`
  `"spring.jpa.hibernate.ddl-auto=create-drop" })` plus `@AutoConfigureMockMvc` and
  `@Transactional`.
- Wires the real controller, `MovementHistoryService`, `MovementHistoryRepository`,
  `AccountLookupService`, `StubAuthorizationService` and the real exception
  handler, and drives it through `MockMvc`. The history service is NOT mocked;
  authorization decisions are the real ones.
- Uses only existing dependencies (`spring-boot-starter-test`, H2) and the
  per-test `properties` pattern already used across the suite; it changes no
  global configuration and adds no file outside the ALLOWED FILES list.
- Asserts that an authenticated non-owner on an existing account and an unknown
  account return byte-for-byte identical `404` bodies including `Content-Type`
  (and any other relevant format metadata).
- This is the test that satisfies the `404`-equality security verification. A
  mocked-service controller test MUST NOT be used as a substitute; if the equality
  is only shown with a mocked service, the verification remains PENDING.

PostgreSQL gate (`MovementHistoryPostgresTest`, extends `AbstractPostgresTest`):

- MANDATORY and ISOLATED: the gate MUST run and pass against a dedicated,
  disposable test database, never a development or production database. By
  default this is an ephemeral Testcontainers PostgreSQL instance (fresh per JVM,
  destroyed at exit). If Docker is unavailable, the existing external
  configuration (`BANCO_PG_JDBC_URL`, `BANCO_PG_USER`, `BANCO_PG_PASSWORD`) MAY be
  used ONLY when it points at a database dedicated to tests.
- The harness never runs `clean` and never drops schema, and no new migration file
  is created. Flyway applies only the EXISTING migrations to that isolated test
  database (current `AbstractPostgresTest` behavior); no migration is applied to
  any development or production database and no development data is touched.
- The gate MUST actually run and pass; a skip does NOT satisfy it. If a suitably
  isolated test database cannot be guaranteed, implementation STOPS and the
  blocker is reported.
- Real `TIMESTAMPTZ` ordering and a multi-page traversal over a STATIC set with an
  exact `(created_at, id)` keyset, `created_at` ties, representative microsecond
  values and negative values near the epoch, asserting no duplicates and no
  omissions WITHIN THAT STATIC SET.
- Microsecond fidelity end-to-end (the stored value equals the cursor's micros).
- The static-set guarantee does NOT extend to concurrent inserts: the approved
  design deliberately does not promise a consistent snapshot across pages, and no
  test asserts otherwise.
- Optional: capture `EXPLAIN` output as evidence for a future index decision.
  NOT DONE: no `EXPLAIN` evidence was captured by this increment, so the
  deferred-index decision still lacks volume evidence. NO index is created
  (ADR-22, "Deferred Index"); no `V12`.

Architecture tests (added to `ModuleBoundariesTest`, additive):

- `financialops.history` (and `..history.web`) MUST NOT depend on
  `accounts.internal`, `accounts.web`, `customers` or `beneficiaries`
  (extends existing rules).
- `financialops.history.web` MUST NOT depend on any `*Repository`.
- `financialops.history.web` MUST NOT depend on `Movement` / `FinancialOperation`.
- Existing `moduleSlicesAreFreeOfCycles` stays green with the new package.

## Security verifications (explicit, before any approval)

- The response contains NO account identifier of any kind: neither the requested
  `accountId` nor any third party's. `accountId` is used only as an internal query
  filter and is never exposed in the returned items. `counterparty` is only a
  generic `{ "type": "TRANSFER" }`.
- The two authenticated `404` responses (unknown vs unauthorized) are verified
  through the REAL path (controller + service + handler) with identical
  byte-level body and format metadata (`Content-Type`), not with a mocked service
  and not merely asserted in documentation.
- `401` occurs only for an unauthenticated actor on an existing account; the
  inherited unauthenticated `401`/`404` asymmetry stays documented, not "fixed".
- No PII, balance, internal state (DEBITED/CREDITED/PARTIAL) or `status` field is
  ever exposed; errors carry safe fixed messages.
- Cursor is validated on decode and is not treated as a secret or as proof of
  authorization.
- The read is `readOnly`; no lock, no write, no audit event.

## Verification commands (executed 2026-10-18)

- `./mvnw -B -ntp verify` (H2 fast suite; architecture, unit and slice tests):
  green on the merge commit with 419 tests, 0 failures and 0 skipped.
  `ModuleBoundariesTest` contributes 65 tests, including the three rules added
  by this increment.
- The PostgreSQL gate (`*Postgres*`) ran and passed: 80 tests over 24 classes,
  0 skipped and 0 failures, with the gate reporting "all executed, none skipped,
  none failed". `MovementHistoryPostgresTest` contributes 4 of those tests.
  A skipped gate would NOT have satisfied it.
- No migration was executed against any real database and no migration file was
  added: the repository contains `V1` through `V11` only, with no `V12`.
- Local runs without Docker and without the `BANCO_PG_*` configuration skip the
  PostgreSQL tests; a skip does not prove anything about PostgreSQL, so the gate
  evidence for this increment is the CI run on the merge commit.

## Stop conditions (halt and escalate, do not improvise)

STOP and request a new explicit approval if any of the following appears:

- A migration or index seems necessary (including `V12`), or Flyway versioning
  would be touched.
- A dependency outside the allowlist is needed (`accounts.internal`,
  `accounts.web`, `customers`, `beneficiaries`, another module's repository).
- Any existing endpoint, error code or module contract would need to change.
- Microsecond precision cannot be preserved end-to-end, or the keyset predicate
  cannot be made to yield exact `(created_at, id)` ordering.
- The two authenticated `404` bodies cannot be made identical.
- The mandatory PostgreSQL gate cannot be run and passed (no Docker and no
  suitable external test database) -> STOP and report the blocker.
- A failing ArchUnit or security test would have to be weakened or suppressed.
- Scope creep appears (filters, exports, alias, auditing, snapshot, locks).
- A fix would require editing a file outside the ALLOWED FILES list above.

## Definition of done (recorded 2026-10-18)

- The endpoint behaves exactly as ADR-22 and `movement-history-design.md` state:
  MET for the implemented and tested scope. The documented integration tests and
  the CI results support the verified behaviours listed in this record. The
  `BANK_EMPLOYEE` authorization path remains NOT VERIFIED END TO END, as
  documented in ADR-22 and `movement-history-design.md`. Two documentation
  clarifications are recorded in ADR-22 (the response exposes no account
  identifier at all, including the requested one; the keyset predicate is
  expressed in a portable explicit form equivalent to the documented row-value
  form).
- All mandatory tests above pass, with the exact commands listed; the PostgreSQL
  gate has actually run and passed (not skipped): MET, see "Verification
  commands".
- No file outside the ALLOWED FILES list is modified; no migration exists: MET.
  The increment touched exactly 15 files (9 production files, 5 test classes and
  the additive architecture test) and added 1442 lines; `V1`...`V11` only.
- The security verifications above are demonstrated by the real-path integration
  test, including the byte-level equality of the two `404` bodies and format
  metadata: MET
  (`MovementHistoryIntegrationTest#unknownAccountAndAuthenticatedNonOwnerReturnByteIdentical404`).
- Documentation status remains accurate; no existing ADR is altered: CLOSED BY
  THIS DOCUMENTATION CHANGE. The three companion documents are brought up to date
  with the merged state, and ADR-22 records the implementation in an appended,
  dated post-implementation note without amending its decision.

## Notes

- This document is a companion to ADR-22 and the specification and shares their
  `Accepted` design status. As a plan it authorized nothing by itself; the
  implementation was authorized separately, delivered in PR #12 and closed on
  2026-10-18, and this document is retained as the execution record.
- Any further change to this capability requires a new, explicitly scoped
  approval. This document does not authorize any code, test, migration, workflow
  or configuration change.
- Grounding: `Movement`/`FinancialOperation` and repositories
  (`financialops.core`), `AccountLookupService` (`accounts.api`),
  `AuthorizationService`/`StubAuthorizationService` (`identity`),
  `AccountTransitionService.findAccountView` (read/auth ordering), existing
  `@WebMvcTest` controller tests and `ModuleBoundariesTest`, as inspected in the
  documentation worktree at `origin/main` `5f66165` (historical: that was the
  revision current when this plan was written; the increment was merged later at
  `19555bae`).
