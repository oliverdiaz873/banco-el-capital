# ADR-21: Transfer via Saved Beneficiary

## Status

Accepted — formally approved by human review (2026-10-13), with all binding decisions and corrections incorporated.

This acceptance covers the architectural direction and the documented acceptance criteria. It is not evidence that concurrency guarantees have been proven, and it does not automatically authorize changes outside the documentary scope.

Existing accepted decisions (ADR-17, ADR-18, ADR-19, ADR-20) remain in force. No existing ADR is amended by this proposal; the amendments described in "Proposed Amendments to Related ADRs" each require separate individual approval. The approval required before creating V11 or modifying code was granted, and Phases 4.1-4.3 implemented the contract; the "proposal only" wording later in this document is historical design text superseded by "Implementation Status". Acceptance of ADR-21 keeps as mandatory conditions the PostgreSQL READ COMMITTED configuration and demonstration, the coordinated concurrency tests, and a PostgreSQL gate executed without omissions in CI with Docker available.

Note on naming: "Alternative B" in the Transfer+Beneficiaries discovery means "transfer explicitly references a saved beneficiary". It is NOT ADR-05's Alternative B ("One Operation plus two Movements", selected in `ADR-05-transfer-model.md:40-50`). The two labels are unrelated.

## Implementation Status (Phases 4.1-4.3)

This section records factual implementation status; it does not change the accepted decisions above or their binding semantics. The earlier "Verified current state at `main@f802c24`" is historical and no longer describes the repository.

- Beneficiary transfers are implemented with the XOR contract. `TransferCommand` carries an optional `beneficiaryId`; `POST /api/v1/transfers` accepts exactly one of `beneficiaryId` or `destinationAccountId`; the client-supplied destination is never used on the beneficiary path.
- `beneficiaries.api.BeneficiaryResolver` exists as the single read-only, owner-scoped contract consumed by `financialops.transfer`. It takes no row lock and exposes only the destination reference, currency, alias and owner embedded in `BeneficiaryResolution`. The ArchUnit exception `financialops.transfer -> beneficiaries.api` is implemented; internal/web access remains prohibited.
- Two-phase authorization order is implemented as follows:
  1. Phase 1 outside `writeTx`: source-holder authorization and, on the beneficiary path, a non-decisive owner-scoped resolution for a fast denial.
  2. Inside `writeTx`: authoritative source-holder projection read, then `decideTransfer`.
  3. On the beneficiary path: authoritative owner-scoped beneficiary projection read, then `decideManageBeneficiary`.
  4. Effective destination and same-account checks use the phase-2 beneficiary destination.
  5. An early, non-decisive idempotency lookup may run before `writeTx`. The decisive re-check runs inside `writeTx` after both phase-2 decisions allow and after the UUID-ordered account legs have acquired both row locks; any tentative leg effects are rolled back before replay, and no operation/movement/idempotency/audit persistence happens before the check.
  6. After both account locks are held, the beneficiary is re-read and its owner, destination and destination currency are compared with the phase-2 snapshot. A mismatch rolls back both legs and persists a business rejection in a fresh balance-free transaction.
- If the source cannot be read or `decideTransfer` denies inside phase 2, the flow throws a uniform `403` with no operation, idempotency row, rejection audit or movements. An unknown source and a foreign source are indistinguishable. A missing/foreign beneficiary after source authorization remains a business `422` with persisted intent evidence, per the accepted rule above.
- The write transaction is pinned to PostgreSQL `READ COMMITTED` via the service-owned `TransactionTemplate` when no caller transaction exists. `REQUIRED` joins an ambient caller transaction; in that case the caller can determine effective isolation and final rollback. This limitation is tested and does not strengthen the ADR-21 guarantee beyond the documented contract.
- V11 created the intent columns and `transfer_beneficiary_usage`. V12 adds `ck_transfer_idem_confirmed_operation` (`outcome <> 'CONFIRMED' OR operation_id IS NOT NULL`) with a fail-closed preflight. Historical rows that violate V12 are inventoried read-only and are not repaired automatically.
- R-2 clarification (Phase 4.3 closure): V13 adds `destination_account_id UUID NULL` to `transfer_operation_idempotency` only. `financial_operations` keeps ADR-18 Option A. New direct `CONFIRMED` and `REJECTED` rows persist the supplied destination; beneficiary rows keep it `NULL` (server-resolved execution evidence remains the linked `CREDIT` movement). Replay and concurrent-winner recovery compare the persisted destination when present; pre-V13 direct rows without it remain under the approved hash-only policy, with no backfill or reconstruction. GET recovery remains hash-only because it has no request body.
- V13's scope check (`destination_account_id IS NULL OR path IS NOT DISTINCT FROM 'direct'`) is validated. Its direct-required check (`path IS DISTINCT FROM 'direct' OR destination_account_id IS NOT NULL`) is `NOT VALID`: it skips initial validation of pre-V13 rows but PostgreSQL enforces it for every new `INSERT` and `UPDATE`. `NOT VALID` does not classify, label, or exempt individual historical rows, and a legacy direct row cannot be updated without inventing evidence. The residual pre-existing gap (a new row with every intent column `NULL` still satisfies V11's historical branch, and V11-Phase 3 rows are all-`NULL` but not historical) is recorded in `docs/risk-register.md`; V13 deliberately adds no C constraint, trigger, or marker.
- The key entropy/custody policy and the conflict-audit failure behavior (A-1) remain OPEN and outside this implementation. See `docs/risk-register.md`.

## Context

Transfer moves value between two ACTIVE single-currency accounts as one `TRANSFER` operation plus exactly two linked movements (DEBIT source + CREDIT destination sharing one `operation_id`), with base source authorization (extended by this ADR with beneficiary-owner authorization), UUID-ordered row locks in one local `writeTx`, dedicated idempotency namespace, and transverse audit (ADR-05 Proposed; ADR-18 Accepted; `docs/transfer-design.md`).

Verified current state at `main@f802c24` (PR #7, clean tree). This block is a
historical snapshot, not the current contract; the Implementation Status and the
R-2 clarification above supersede it wherever they differ (including replay
field comparison and V13 destination evidence):

- Transfer accepts only `sourceAccountId, destinationAccountId, amountMinorUnits, currency`: `financialops/transfer/web/CreateTransferRequest.java:9-13`. No `beneficiaryId` is accepted or resolved anywhere in `financialops/transfer/` (verified by code inspection of `TransferController.java:29-75` and `TransferService.java:1-511`).
- Transfer contract: `TransferController.create:42-54` requires `Idempotency-Key` (400 when missing), builds `AuthenticatedActor(actorId, Set.of())`, returns `201 created=true / 200 replay`; recovery `GET /transfer-operations/{key}` requires `Idempotency-Key-Hash` (`TransferController.java:59-73`); `TransferService.transfer():96-196` server-resolves both holders via `AccountLookupService.findHolderCustomerId` (`AccountLookupService.java:26-32`, read-only `findById`, no lock), authorizes `decideTransfer(actor, sourceHolder)` only (`TransferService.java:131-134`), canonical hash `source-lower|dest-lower|amount|currency-upper` SHA-256 (`TransferService.java:432-449`), pre-lock fast-path + post-lock re-check after both locks (`TransferService.java:136-142,284-286`), UUID-ordered leg invocation (`TransferService.java:240-279`), atomic persist of operation + 2 movements + idempotency + `TRANSFER_CONFIRMED` audit in one `writeTx` (`TransferService.java:287-335`), `LegRejected` fresh-tx rejection (`TransferService.java:146-171,479-484`), `conflict()` in its own `writeTx` (`TransferService.java:373-389`), `replay()` (`TransferService.java:391-397`).
- Replay compares hash only, not fields: `TransferService.transfer():138-141,186-189` compares `getRequestHash().equals(hash)`; field-by-field comparison does not exist today.
- Debit/credit legs are Accounts-owned contracts joining the outer tx: `AccountDebitService.applyDebit:28-49` and `AccountCreditService.applyCredit:27-46` (`@Transactional`, REQUIRED), each `findByIdForUpdate` (`AccountRepository.java:19-21` `PESSIMISTIC_WRITE`) then re-verifies existence/operability (`isOperable()==ACTIVE`, `Account.java:102-104`)/currency/funds (debit only) before `Math.addExact/subtractExact`.
- Representation Option A (ADR-18, no migration): `FinancialOperation.account_id` = SOURCE; destination reconstructed from linked CREDIT movement; no `destination_account_id` column (`V8__transfer_foundation.sql:1-15`; `TransferService.java:288-312`).
- Audit today: only `TRANSFER_CONFIRMED` (details `source=.. destination=.. amount=.. currency=..`), `TRANSFER_REJECTED` (resourceId=key, details=reason), `TRANSFER_CONFLICT` (own tx) — all in `TransferService.java:316-330,357-364,376-383`. No beneficiary field exists because beneficiary transfer does not exist.
- Safe-error mapping (identical in both handlers): `TransferExceptionHandler.java:23-36` and `BeneficiaryExceptionHandler.java:23-36` map `UNAUTHENTICATED→401, FORBIDDEN→403, REJECTED→422, CONFLICT→409, FAILED/UNKNOWN→500` with same-key recovery messages. Recovery-query absence/hash-mismatch resolves to `404` (`TransferController.findByKey:59-73` → `findAuthorizedByKey:212-215` returns empty before auth).
- Beneficiaries v1 (ADR-20 Accepted): `(owner_customer_id, destination_account_id, alias, destination_currency snapshot)`, `UNIQUE(owner,destination)` only, no FK (`V10__beneficiary_foundation.sql:8-27`; `Beneficiary.java:17-41`); owner always `actor.subject()` server-resolved (`BeneficiaryService.java:81,166,182,198`); `decideManageBeneficiary` before any idempotency read/write (`BeneficiaryService.java:82-85`); minimal `AccountDestinationLookup.findDestination -> Optional<DestinationCurrency>` (existence+currency only, `AccountDestinationLookup.java:26-34`), never holder/status/balance; hash `owner-lower|dest-lower|alias-normalized-verbatim` (`BeneficiaryService.java:378-393`); endpoints `POST/GET/GET{id}/DELETE /api/v1/beneficiaries` + `GET /beneficiary-operations/{key}` (`BeneficiaryController.java:41-105`); delete is physical + `BENEFICIARY_REMOVED` audit in one `writeTx` (`BeneficiaryService.java:197-221`); `BENEFICIARY_REMOVED details` stores only `owner=` (`BeneficiaryService.java:211-218`), so it does NOT preserve the full historic payload (alias/destination/currency).
- No `beneficiaries.api` package exists (only `beneficiaries/internal/` + `beneficiaries/web/`). No beneficiary row lock exists anywhere: the only `PESSIMISTIC_WRITE` in the system is `AccountRepository.java:19-21`, used by account debit/credit/transition services; `BeneficiaryRepository.java:9-17` exposes only non-locking reads. `financialops -> beneficiaries` is negatively asserted: `ModuleBoundariesTest.financialOpsMustNotDependOnBeneficiaries:168-178`; `outsideBeneficiariesMustNotDependOnBeneficiariesInternal:714-723`; `webMustNotDependOnBeneficiaryEntity:738-747`; slices cycle-free (`750-753`).
- Isolation is not declared anywhere: `application.yml:1-19` sets no isolation level, and no `Isolation` property was found in main sources. The effective level is therefore the engine default (unasserted in-repo; PostgreSQL default is a deployment fact, not a repository guarantee).
- Key generation policy is OPEN: `ADR-06:19` defers client-supplied key generation/assignment policy. No entropy requirement exists today.
- `Idempotency-Key-Hash` (recovery header) IS the stored intent hash: `transfer-design.md:235,272` defines recovery as requiring "the 64-char hex above", i.e. the canonical request hash. It is `SHA-256` over intent fields — reproducible from known fields, NOT an independent secret. Recovery security rests on the PAIR (Key in path + Hash in header).
- Stub auth limits: `X-Actor-Id` + `new AuthenticatedActor(id, Set.of())` is self-asserted and spoofable (ADR-09; `TransferController:53`, `BeneficiaryService.java:79-85`); accepted for MVP only with owner-scoped, disclosure-safe semantics. Key entropy protects against enumeration; it does NOT replace authorization or access control.

Decision drivers: (a) let a customer pay a saved destination without pasting UUIDs, preserving direct transfers; (b) prove which beneficiary was used after renames/deletes; (c) add no money invariant risk; (d) keep idempotency deterministic across beneficiary changes including deletions and rejections; (e) keep module boundaries explicit and testable.

## Proposed Scope

Allow a customer to initiate a transfer using a saved beneficiary while preserving direct `destinationAccountId` transfers. This ADR defines behavior only.

Explicit non-goals: external-bank/interbank beneficiaries; FX, fees, new limits; alias editing; new account lifecycle behavior; ledger redesign; production JWT/auth replacement; microservices, Kafka, distributed transactions, sagas; changes to existing financial-operation semantics; refactoring unrelated modules; changes to migrations V1-V10; implementation or deployment work.

## API Contract - Considered Alternatives and Proposal

Alternatives compared on compatibility, clarity, security, traceability, complexity:

1. **XOR contract (approved direction): accept exactly one of `beneficiaryId` or `destinationAccountId`.**
   - Pros: preserves all direct-transfer clients byte-for-byte; one unambiguous resolution path per request; traceability is per-request explicit; least idempotency ambiguity.
   - Cons: one new nullable field + mutual-exclusion validation; clients must learn the rule; response/audit must record which path was used.
2. **Beneficiary-only (rejected for this increment): require `beneficiaryId` for all new transfers.**
   - Pros: single path, maximal traceability.
   - Cons: breaks every direct-transfer client; forces a deprecation/migration with no product justification; contradicts ADR-05 leaving direct-vs-beneficiary-only open. Rejected without a migration mandate.
3. **Validated coexistence (rejected): allow both fields.**
   - No precise safe contract justifies it: if both are present they can agree (redundant), disagree (which wins?), or partially overlap. Any silent priority is ambiguous and enumerable. Rejected; both-present MUST be a deterministic input refusal, not a precedence rule.

Approved XOR handling:

- Neither field present: `400` input error (malformed shape, mirrors bean-validation `400`s), persists nothing.
- Both fields present: `400` input error (ambiguous request, persists nothing). Not a business rejection (`422`) nor a key conflict (`409`); the request shape itself is invalid.
- Malformed UUID / malformed JSON (including non-UUID `beneficiaryId`): `400`, persists nothing (shape layer, as today).
- `beneficiaryId` path with unknown beneficiary (missing row, unresolvable): deterministic business `REJECTED` (`422` per `TransferExceptionHandler:23-30`), no movement, no balance change; rejection operation + idempotency + audit persisted (intent evidence per §Persistence). This assumes source authorization already succeeded: per H-04, an unknown or unauthorized ORIGIN is a uniform `403` with no persisted evidence. An unknown DESTINATION after source authorization remains a `422` rejection.
- Beneficiary owned by another customer: indistinguishable from unknown — same `422`, same generic body (e.g. `"invalid beneficiary"`), no existence/owner detail; no third-party owner persisted (owner snapshot NULL by design, §Authorization). `401/403` are reserved for actor/source-holder failures, not beneficiary existence.
- Resolved destination no longer exists (possible: logical refs, no FK): `REJECTED` (`422`), no effects — same as unknown-account path, evaluated under locks.
- Resolved destination BLOCKED/CLOSED: `REJECTED` (`422`), no effects. Saving a BLOCKED destination is legal (ADR-20 precision A, proven by `BeneficiaryPostgresIdempotencyTest.blockedDestinationRemainsSavable`); execution-time operability governs. This applies ADR-18 transfer-scoped `ACTIVE->ACTIVE` plus ADR-19 P1.
- Direct-transfer clients: unchanged path, unchanged hash/audit semantics, full backward compatibility. No migration required for existing callers.

Response: `TransferResponse(operationId, created)` unchanged; whether to additionally expose the resolved `beneficiaryId`/`destinationAccountId` in the response is left to implementation review (reconstruction MUST be possible via recovery/audit regardless).

## Security and Authorization

Two separate requirements; the second MUST NOT replace or weaken the first:

1. `decideTransfer(actor, sourceHolder)` MUST allow (existing `TransferService.java:131-134,225` semantics, stub self-or-`BANK_EMPLOYEE`).
2. `decideManageBeneficiary(actor, storedBeneficiaryOwner)` MUST allow for the beneficiary path whenever a valid owned beneficiary is evidenced (existing `BeneficiaryService.java:82-85,152` semantics).

- Actor and owner are server-derived: `actor.subject()` from `X-Actor-Id` stub; beneficiary owner from the stored row (new requests) or the persisted snapshot (recovery), never from the request body (mirrors `BeneficiaryService.java:79-81` and transfer holder rule `TransferService.java:112-115`).
- Resolution is owner-scoped: lookup MUST be `findByIdAndOwnerCustomerId`-equivalent; missing vs foreign are indistinguishable.
- New transfer: two phases. Phase 1 outside `writeTx` (source-holder fast-path auth + beneficiary-owner non-decisive read, only for fast `401/403`; decides no money). Phase 2 inside `writeTx` (authoritative owner-scoped resolution + re-evaluation of BOTH decisions before legs/idempotency). Denial at either phase persists nothing.
- Recovery (POST replay with existing key, or `GET /transfer-operations/{key}` + hash): authorizes against PERSISTED snapshots, never the live row. `decideTransfer(actor, persistedSourceHolder)` is always required. `decideManageBeneficiary(actor, persistedBeneficiaryOwner)` is additionally required WHENEVER a valid owned beneficiary is evidenced (owner snapshot present). For invalid-beneficiary rejections the owner snapshot is NULL by explicit design — no third-party owner is ever persisted — and recovery requires ONLY source-holder authorization on the persisted source (see next paragraph). A missing row never widens access. Key entropy does not substitute authorization.
- Invalid-beneficiary rejections (explicit rule, not an omission): unknown beneficiary → persist attempted `beneficiaryId` + owner NULL + generic reason; foreign beneficiary → persist attempted `beneficiaryId` + owner NULL (never the real third-party owner) + same generic reason and same `422` body as unknown. Recovery of such rejections requires only the attempter's source-holder authorization: there is no owned resource to authorize (unknown case), and persisting the third party's owner would itself leak (foreign case). The attempter already knew the ID they sent, so returning "your attempt with that ID was rejected" reveals nothing new; a different actor cannot pass the persisted-source-holder gate.
- A saved beneficiary is NOT permanent authorization to move money. Existence at save time proves nothing at execution time; operability, currency, funds, and both authorizations are revalidated under the transfer's consistency unit.
- Stub limits are restated, not solved: `X-Actor-Id` remains spoofable; the proposal relies on the same owner-scoped, disclosure-safe construction accepted in ADR-20 and adds no new trust in client-supplied owner/holder ids. Production identity stays out of scope.

## Transactionality and Concurrency

Preserved invariants (ADR-18): one `TRANSFER` operation; exactly one DEBIT (source) + one CREDIT (resolved destination) sharing `operation_id`; atomic commit/rollback; no partial debit/movement on rejection; operability/currency checks; UUID-ordered row locks; same-account/funds/overflow protections; idempotency/recovery guarantees.

Verified current tx model (`TransferService.java:73-94,238-336`): outer `TransactionTemplate writeTx` is the only committing unit; `applyDebit/applyCredit` join via REQUIRED; explicit `flush()` before decision; winner reload in fresh read-only `readTx` (PostgreSQL aborts whole tx on violation).

Approved semantics B (binding product rule): beneficiary deletion revokes FUTURE uses; it does not invalidate a transfer already past its validation point. No beneficiary row lock is taken in this increment (pessimistic beneficiary locking rejected: it would change the proven delete path, add a third lock per transfer, and promise immediate revocation nobody requested).

Validation point (binding, NOT a mutual-exclusion claim): transfer SHALL resolve owner-scoped on `writeTx` entry and SHALL re-validate owner-scoped existence after both account row locks are held, adjacent to the existing post-lock idempotency re-check (`TransferService.java:284-286` pattern extended). The second read is the validation point. Double-read narrows but does NOT close the validation-to-commit window; the window is accepted by design: a delete committed before the second read yields `REJECTED`; a delete committed after it does not invalidate a confirmation built on the validated snapshot (the delete still succeeds and revokes future uses).

Isolation requirement (binding): the validation point REQUIRES PostgreSQL READ COMMITTED statement-snapshot semantics — a delete committed before the second read MUST be visible at that read. The phrase "READ COMMITTED or stronger" SHALL NOT be used: under stable-snapshot levels (e.g. REPEATABLE READ) the second read may miss an already-committed delete. The level SHALL be pinned in configuration/documentation and proven by the coordinated barrier tests (§Testing) before acceptance. Until pinned and proven, the only committed guarantee is the product rule over allowed final states; no engine visibility is claimed.

No `accounts.internal` access from transfer; no dependency on deposit/withdrawal; no distributed transaction; no new lock primitive beyond the already-deferred additive `lockBothInUuidOrder` proposal point (still NOT decided).

## Idempotency and Recovery

Terminology (binding): `Idempotency-Key` = client-supplied opaque key (recovery path `{key}`, VARCHAR(128), the unguessable lookup factor). `requestHash` = SHA-256 over canonical intent fields (64 hex). `Idempotency-Key-Hash` (recovery header) = the `requestHash` value supplied back. The header is an integrity check, NOT an independent secret: it is reproducible from known intent fields. Recovery security rests on the PAIR (Key, Hash).

Key requirements (binding, to be normed in the API contract and recorded as an ADR-06 amendment proposal — not left as a recommendation): `Idempotency-Key` SHALL be generated by a cryptographically secure pseudorandom generator with at least 122 bits of entropy (e.g. UUIDv4 per RFC 4122 §4.4, or 16+ bytes from the platform CSPRNG), transmitted only over TLS, stored as a secret by the requester, and treated as a bearer credential: possession of the (Key, Hash) pair authorizes recovery subject to the account/beneficiary checks below. Sequential, time-derived, or otherwise guessable keys do not satisfy this requirement. Key-management (generation, transmission, custody, rotation by issuing a new key — never reusing a compromised key for another intent) belongs to the real contract.

Intent hash (binding): the beneficiary-path hash covers ONLY client-supplied intent — `beneficiary-transfer | source-lower | beneficiaryId-lower | amount-base10 | currency-upper` (exact order/separator/encoding fixed here; mirrors `TransferService.requestHash:432-449` and `BeneficiaryService.requestHash:378-393` construction). The resolved destination is execution evidence, NOT identity: hashing it would make replay depend on server state and recreate the deleted-beneficiary contradiction. The path discriminator (`beneficiary-transfer|` vs `direct|`) is mandatory so direct and beneficiary intents can never hash-equal. The direct path keeps its existing hash shape.

Two paths (binding):

- **Existing key:** (1) load `transfer_operation_idempotency` by key — no observable yet. (2) POST: run recovery authorization against persisted snapshots FIRST; denied → indistinguishable denial (`403`; never `409`, never replay, never a distinct `404`). GET: record absent OR hash mismatch → identical `404` (no auth performed — the 64-char pair is the unguessable gate); hash match → recovery authorization (`401/403`). The POST/GET asymmetry is intentional: POST has no prior secret (the server derives the hash from the body), so authorizing first is what prevents probing via `409`-vs-`200`; GET has the client-supplied secret, so hash-first-then-auth is safe. (3) Authorized → compare stored hash AND stored intent fields (source, beneficiary-or-destination id per path, amount, currency, path discriminator): match = replay stored result with zero new effects and zero new effect audits (`200 created=false`, or rethrow stored `REJECTED`); mismatch = `409 CONFLICT` with own-transaction `TRANSFER_CONFLICT` audit, idempotency untouched, zero money movement. The live `beneficiaries` table is NEVER consulted on this path. The hash never substitutes field comparison and vice versa.
- **New key:** validate shape (`400`, nothing persists) → phase-1 auth → `writeTx`: resolve vigente, phase-2 dual auth, legs in UUID order of the RESOLVED pair (economic effect simultaneous at commit), post-lock idempotency re-check plus beneficiary second read, atomic persist (operation + DEBIT + CREDIT + idempotency intent row + usage row iff CONFIRMED + audit) + `flush()`. Second-leg refusal rolls back then persists `REJECTED` in a fresh balance-free `writeTx` (existing `LegRejected` pattern).

Replay after beneficiary deletion returns the stored outcome from snapshots; a new key over a deleted beneficiary resolves afresh (expected `REJECTED`). The same key SHALL NEVER produce two financial effects.

`CONFIRMED/REJECTED/CONFLICT/FAILED/UNKNOWN` keep existing persisted-vs-caller-side meanings: only `CONFIRMED/REJECTED` persisted as outcomes (`TransferOperationOutcome.java:4-6`); `CONFLICT` persists only its audit; `FAILED` only determined-no-effect; `UNKNOWN` never persisted, retry with same key.

## Persistence and Migrations

Options:

- (a) Nullable beneficiary reference on the transfer operation (rejected for this increment): touches the `financial_operations` row shared with deposit/withdrawal (blast radius beyond transfer) and invites duplicating `resolvedDest`.
- (b) Dedicated `transfer_beneficiary_usage` table (approved direction, hypothesis — NO SQL here, NO migration created): `(operation_id PK logical → financial_operations, no physical FK; beneficiary_id UUID NOT NULL logical, no FK; beneficiary_owner VARCHAR(64) NOT NULL snapshot; beneficiary_alias VARCHAR(64) NULL snapshot; created_at)`. One row per CONFIRMED beneficiary transfer; no row for direct transfers and no row for REJECTED attempts (no usage occurred). Ownership: transfer feature. Backward compatible.
- (c) Audit-only reference: rejected — `TRANSFER_* details` lack beneficiary fields and audit is not the financial record of intent; ADR-05/11 require the operation-side evidence to reconstruct which beneficiary was used, independently of log retention.

Minimum evidence, split by design (binding): INTENT lives in the transfer idempotency row for EVERY outcome (`CONFIRMED` and `REJECTED`): source, beneficiary-or-destination id as supplied, amount, currency, path discriminator, requestHash — so a repeated key can be proven same-or-different intent without the live row and without trusting the hash alone. USAGE lives in `transfer_beneficiary_usage` and exists ONLY for `CONFIRMED` beneficiary transfers: `beneficiary_id` (which was used — ADR-05/11 requirement), `beneficiary_owner` snapshot (recovery authorization after delete; not derivable elsewhere), `beneficiary_alias` snapshot (which-named-reference UX history; lost on physical delete since `BENEFICIARY_REMOVED` stores only `owner=`). NOT duplicated: `resolvedDest` (derived from the linked `CREDIT` movement, Option A), amount/currency/actor/source/timestamps (operation + audit). No holder/balance/status/PII beyond current details.

If additive storage is needed, it is proposed as **V11 (proposal only)**. No SQL is written here; no migration V1-V10 is touched; V11 is NOT created under the current approval. Index/uniqueness: no new uniqueness beyond existing PKs (idempotency key remains the arbiter); a secondary index on `beneficiary_id` for traceability only (not uniqueness) may be proposed at implementation review.

## Audit and Historical Reconstruction

For a confirmed beneficiary-based transfer, the committed evidence MUST allow reconstruction of: source account, resolved destination actually credited (via linked `CREDIT` movement), `beneficiaryId` used (usage row), currency/amount snapshot at execution (operation), outcome, actor, operation id, owner/alias snapshots (usage row), timestamps/correlation — following `TRANSFER_CONFIRMED` shape (`TransferService.java:316-330`) extended with beneficiary fields (exact field names at implementation review).

- Operation history (financial record) vs current beneficiary record vs audit log stay separated per ADR-11. Deleting a beneficiary MUST NOT erase the transfer's evidence; the transfer's snapshots are self-sufficient.
- Rejected/conflict/replay rules preserved: `TRANSFER_REJECTED` + idempotency `REJECTED` with no movements; `TRANSFER_CONFLICT` own-tx audit only; replay creates no new audit (existing `TransferService` patterns). No new audit event is proposed except the extended `TRANSFER_CONFIRMED/REJECTED` payloads; a dedicated `TRANSFER_BENEFICIARY_RESOLVED` event is NOT proposed.
- Conflict audit boundary (binding): on same-key/different-intent for an authorized actor, the `TRANSFER_CONFLICT` audit commits in its own independent local transaction writing ONLY the audit event — it never modifies the original idempotency row and never creates financial effects. The `409` is returned ONLY if that audit transaction commits. If the audit write itself fails, the implementation SHALL NOT report 409-with-evidence; it SHALL report `500 UNKNOWN` with the same-key recovery message, persist nothing new, and leave the original idempotency record untouched. Conflict-audit execution SHALL NOT introduce observable differences (timing, error text, audit count) that let unauthorized actors distinguish absent-key from unauthorized from conflicted.

## Module Boundary and Public Contract

Minimum viable `beneficiaries.api` (conceptual, NOT created): a read-only, owner-scoped resolution contract, e.g. `resolveForOwner(beneficiaryId, ownerCustomerId) -> Optional<BeneficiaryResolution(destinationAccountId, destinationCurrency, aliasSnapshot)>`, plus no other methods. Read-only (`@Transactional(readOnly=true)`), no row locks — consistency comes from the transfer-side double read, not the contract. It MUST expose only what Transfer needs; it MUST NOT expose repositories, JPA entities (`Beneficiary`, `BeneficiaryCreationIdempotency`), holder data of the destination, balances, or account status. Exact name/shape is implementation detail within these bounds and MUST live in `beneficiaries.api` (new) under ADR-17 `api/`-only-with-consumer rule.

Required rule changes (described here, NOT applied):

- Permit narrowly `financialops.transfer -> beneficiaries.api` (resolution contract only).
- Keep prohibited: `financialops -> beneficiaries.internal`, `financialops -> beneficiaries.web`, `beneficiaries -> financialops.*` (existing `beneficiariesMustNotDependOnFinancialOps`), `core -> transfer/beneficiaries`, any `..web -> ..web` cross-use, `web -> Repository/Entity`, `moduleApis -> web`, cycles.
- No `shared` package; no core-to-feature dependency.

Until approved, `financialOpsMustNotDependOnBeneficiaries` stays in force and implementation MUST NOT begin.

Alternative without `beneficiaries.api` (client pre-resolution) was considered: it respects boundaries trivially but fails traceability (server cannot prove which beneficiary was used) and is therefore NOT recommended as the beneficiary-transfer design; it remains the status-quo workaround.

## Testing and Acceptance Criteria

Design only — no test claims made. Required matrix (H2 + PostgreSQL `postgres:16-alpine` Testcontainers gate, coordinated barriers, no `sleep()`, no skipped PG tests in CI):

- Direct-transfer compatibility (existing `Transfer*Test` + `TransferPostgres*` suites reused verbatim; XOR `destinationAccountId` path MUST behave identically).
- Valid owned-beneficiary transfer (new): confirms with 1 op + DEBIT source + CREDIT resolved dest, UUID-order preserved for resolved pair, audit carries beneficiary ref + snapshot, usage row created.
- Missing beneficiary (new): deterministic `REJECTED` with the REJECTED-evidence trio (operation + idempotency intent row + audit) and ZERO FINANCIAL effects (zero movements, zero balance changes, zero `CONFIRMED` rows) — not "zero rows".
- Foreign beneficiary (new): indistinguishable from missing (same status/body, owner NULL persisted, real third-party owner never stored).
- Deleted beneficiary: (i) delete committed before validation point → `REJECTED` with trio, no usage row; (ii) delete committed after validation point → `CONFIRMED` with usage row + delete effective afterwards. Post-commit replay with same key (both cases incl. REJECTED) returns stored outcome without consulting `beneficiaries`, zero new rows/audits.
- Resolved destination missing/BLOCKED/CLOSED (new/extension): `REJECTED` with trio, zero financial effects; direct-path tests reused where overlapping.
- Missing/both-present fields, malformed UUID (new): `400`, persists nothing.
- Same-key replay same intent (new): single effect ever, `created=false`, no new audit.
- Same key different intent (new): `409` + `TRANSFER_CONFLICT` (own tx), idempotency untouched, no money; unauthorized actor gets indistinguishable denial, never `409`.
- Recovery auth (extension): AND-of-snapshots when owner present; source-only with explicit justification when owner NULL by design; destination-holder alone learns nothing; hash-mismatch ≡ absent (`404`).
- Atomicity/zero-financial-effects (extension of `TransferAtomicityTest` + `TransferPostgresRollbackTest` patterns): failure between resolution/legs/audit leaves the REJECTED-or-nothing evidence per outcome with zero financial effects, on H2 AND PostgreSQL (PostgreSQL aborts the whole tx on violation — the proof is mandatory, not optional).
- Concurrency (extension of `TransferConcurrencyTest` + `TransferPostgresConcurrencyTest`): same-key convergence (1 op + 2 movs), funds competition, opposite resolved-pair transfers without deadlock, isolation-visibility barrier test per §Transactionality (delete-between-reads → `REJECTED` under pinned READ COMMITTED; delete-after-validation → `CONFIRMED` + delete effective).
- Audit reconstruction after beneficiary deletion (new): operation + CREDIT + usage + audit alone reconstruct source/resolved-dest/beneficiary/owner/alias; `BENEFICIARY_REMOVED` alone is insufficient by itself.
- PostgreSQL specifics (new): V11 (if approved) schema/constraints, tx-abort winner-reload, lock ordering on resolved pairs, reconciliation `balance == credits - debits` per leg including beneficiary transfers.
- ArchUnit (new + regression): new `transfer -> beneficiaries.api` allowance + continued prohibitions; full `ModuleBoundariesTest` regression.

Acceptance: all criteria above proven on H2 AND real PostgreSQL with the `*Postgres*` gate executed and none skipped in CI, under the pinned isolation level. This document asserts no passing tests.

## Consequences and Trade-offs

- Easier: beneficiary UX without pasting UUIDs; server-proven beneficiary use; bounded additive evolution (one contract + intent columns + one usage table + payload extensions).
- Harder: one more cross-module edge to own; idempotency identity has path-dependent shape; recovery matrix grows (POST vs GET orders); V11 (if approved) + PG barrier proofs mandatory; between-resolution-and-commit reasoning required in every future transfer change.
- Trade-offs: XOR explicitness now vs coexistence flexibility that invites ambiguity; intent-in-idempotency + usage-only-if-CONFIRMED now vs audit-only fragility later; narrow `beneficiaries.api` now vs speculative generality (rejected per ADR-17); snapshot-accepted window now vs pessimistic beneficiary locks with delete-path changes (rejected for this increment).

## Alternatives Considered

- Beneficiary-only with direct-transfer deprecation -> Rejected for this increment (breaks clients, no mandate).
- Both-fields coexistence with silent precedence -> Rejected (ambiguous, enumerable).
- Client pre-resolution with no server contract -> Rejected as the design (kept as status-quo workaround; fails traceability).
- `financialops` direct read of `beneficiaries` tables/entities -> Rejected (violates ADR-01/17/20 + ArchUnit).
- Hash with resolved destination -> Rejected (recreates the deleted-beneficiary replay contradiction; resolved destination is evidence, not identity).
- Hash without path discriminator -> Rejected (direct-vs-beneficiary collision risk).
- Trusting stored hash alone without field comparison -> Rejected (hash never substitutes intent-field comparison).
- Audit-only beneficiary reference -> Rejected (fails ADR-05/11 reconstruction independence).
- Pessimistic beneficiary locking coordinated with delete -> Rejected for this increment (changes proven delete path, third lock per transfer, promises unrequested immediate revocation).
- "READ COMMITTED or stronger" -> Rejected as wording (stable-snapshot levels break the required visibility; pin READ COMMITTED specifically).
- Treating `Idempotency-Key-Hash` as an independent secret -> Rejected (reproducible from fields; security rests on the (Key, Hash) pair + key entropy + authorization).
- Generic balance applicator / shared idempotency namespace / overdraft / FX / BLOCKED-credit generality / ledger / sagas / microservices -> Rejected per ADR-05/15/16/18 (unchanged).

Explicit non-decisions: exact Java signatures, exact DDL/indexes beyond the V11 hypothesis, exact JSON field names beyond the XOR shapes, retention/cleanup, recovery-job implementation, isolation pinning location (config vs docs) beyond the requirement itself.

## Relationship to Existing ADRs

- ADR-01/17: proposes the narrow `transfer -> beneficiaries.api` allowance described above; no other boundary change.
- ADR-02/04/05: applies 1-op+2-movements, lifecycle, beneficiary-as-reference + retain-enough-reference; leaves ADR-05 direct-vs-beneficiary-only open except for this proposed increment.
- ADR-06: extends determinism to a path-discriminated intent hash in the existing transfer namespace (no new namespace proposed); proposes norming key-entropy generation policy.
- ADR-07/15/16/18: reuses check-after-lock, stored-balance, dedicated-contracts, UUID-order, atomic op+movements+idempotency+audit discipline; fills only the resolution-ordering step plus the pinned READ COMMITTED requirement at the validation point.
- ADR-03/19: execution-time operability (`ACTIVE->ACTIVE`, P1) governs resolved destinations; save-time stays permissive per ADR-20.
- ADR-09/10/11/13: dual authorization (with explicit NULL-owner rule for invalid rejections), versioned REST + safe errors (with POST/GET order distinction), transverse audit with snapshot reconstruction and explicit conflict-audit boundary, risk/invariant + PG barrier testing. No provider, distribution, or ledger change.

No existing ADR is modified by this proposal.

## Proposed Amendments to Related ADRs (proposals only — NOT applied, NOT accepted)

1. **ADR-01** (Proposed): allowed-edges gains `Financial Operations -> Beneficiaries.api (transfer-time owner-scoped resolution only, via contract, inside the transfer local tx; no direct state access)`.
2. **ADR-17** (Accepted): `beneficiaries` section gains `api/` with the single read-only resolution contract plus the negative assertions (§Module Boundary); records the narrow exception to `financialOpsMustNotDependOnBeneficiaries` (`transfer -> beneficiaries.api` only).
3. **ADR-06** (Proposed): path-discriminated intent-hash shapes in the existing transfer namespace; key-entropy generation/custody requirement (CSPRNG, ≥122 bits, TLS, bearer treatment).
4. **ADR-11** (Proposed): extended `TRANSFER_CONFIRMED/REJECTED` payloads (beneficiary fields, snapshots self-sufficient); conflict-audit boundary rule (own tx, audit-only, 409-iff-committed else 500-UNKNOWN).
5. **ADR-18** (Accepted): addendum — beneficiary validation point (resolve on entry + re-validate post-locks), post-lock re-check extended to the intent hash, pinned READ COMMITTED requirement for the validation point. No change to account lock ordering or financial invariants.
6. Reference-only (no amendment unless the reviewer demands): ADR-02/04/05, ADR-03/19, ADR-09/10/13, ADR-15/16, ADR-08/14, ADR-19/20 (applied, unamended).

## References

- `docs/adr/ADR-05-transfer-model.md` (Proposed), esp. beneficiary-as-reference + open direct-vs-beneficiary-only.
- `docs/adr/ADR-18-transfer-atomicity-and-lock-ordering.md` (Accepted) + `docs/transfer-design.md` (esp. recovery `Idempotency-Key-Hash` = 64-char intent hash).
- `docs/adr/ADR-20-beneficiary-saved-destinations.md` (Accepted) + `docs/beneficiary-design.md` (esp. future Alternative B note).
- `docs/adr/ADR-17-feature-based-packaging.md` (Accepted); `ADR-01` (Proposed); ADR-02/03/04/06 (`:19` key-policy OPEN)/07/08/09/10/11/13/14/15/16 (Proposed); ADR-19 (Accepted).
- Implementation: `src/main/java/com/bancoelcapital/financialops/transfer/TransferService.java`, `.../transfer/web/{TransferController,CreateTransferRequest,TransferResponse,TransferOutcomeResponse,TransferExceptionHandler}.java`, `.../transfer/{TransferCommand,TransferResult,TransferOutcome,TransferOperationOutcome,TransferOperationException,TransferOperationIdempotency,TransferOperationIdempotencyRepository}.java`, `src/main/java/com/bancoelcapital/accounts/api/{AccountDebitService,AccountCreditService,AccountLookupService,AccountDestinationLookup,DestinationCurrency}.java`, `src/main/java/com/bancoelcapital/accounts/internal/{Account,AccountRepository}.java`, `src/main/java/com/bancoelcapital/financialops/core/{FinancialOperation,Movement,FinancialOperationRepository,MovementRepository,FinancialOperationType,FinancialOperationStatus,MovementDirection}.java`, `src/main/java/com/bancoelcapital/beneficiaries/internal/{Beneficiary,BeneficiaryRepository,BeneficiaryService,BeneficiaryCommand,BeneficiaryResult,BeneficiaryOutcome,BeneficiaryCreationOutcome,BeneficiaryView,BeneficiaryException,BeneficiaryCreationIdempotency,BeneficiaryCreationIdempotencyRepository}.java`, `src/main/java/com/bancoelcapital/beneficiaries/web/{BeneficiaryController,CreateBeneficiaryRequest,BeneficiaryResponse,BeneficiaryOutcomeResponse,BeneficiaryDetailsResponse,BeneficiaryExceptionHandler}.java`, `src/main/java/com/bancoelcapital/identity/{AuthenticatedActor,AuthorizationService,AuthorizationDecision,StubAuthorizationService}.java`, `src/main/java/com/bancoelcapital/audit/api/{AuditRecorder,AuditEntry}.java`, `src/main/resources/db/migration/V8__transfer_foundation.sql`, `src/main/resources/db/migration/V10__beneficiary_foundation.sql`, `src/main/resources/application.yml` (no isolation declared), `src/test/java/com/bancoelcapital/architecture/ModuleBoundariesTest.java`.

## Open Questions (require explicit human approval before implementation)

1. Intent-hash exact shape with path discriminator (recommended as written).
2. Intent columns on the idempotency row + usage-only-if-CONFIRMED split.
3. POST/GET recovery order as binding text.
4. NULL-owner + source-only recovery for invalid rejections (explicit rule).
5. Dedicated usage table `(operation_id, beneficiary_id, owner, alias)`, destination derived.
6. Pinned READ COMMITTED + barrier proof as acceptance gate.
7. `resolveForOwner` 5-field read-only contract without locks.
8. Key-entropy requirement location (API contract + ADR-06 amendment).
9. Whether to formally accept ADR-21 and each related amendment individually per project procedure.
10. Whether to proceed to V11/implementation planning (not authorized by this document).

## Notes

- Language: MUST for proposed rules; SHOULD for recommendations; MAY for future possibilities.
- Compatibility at draft time: main@f802c24; ADR-01..ADR-20 states verified (`17/18/19/20 Accepted`, rest Proposed).
- Approval record: ADR-21 formally accepted by human review 2026-10-13 with the corrections in §§Transactionality/Idempotency/Authorization/Persistence/Audit/Testing incorporated; acceptance of each related amendment (ADR-01/17/06/11/18) remains pending individual approval per project procedure.
- Recorded stale items (not fixed): ADR-14 `No ADR-15`, `README.md` setup-only text, `MovementDirection`/`FinancialOperationStatus` javadocs, ADR-01 Proposed vs ADR-17 Accepted inversion.
