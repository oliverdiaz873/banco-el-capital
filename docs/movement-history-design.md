# Feature Documentation: Account Movement History (read-only)

## Status

Accepted - design contract implemented. This document records the approved API
contract and the semantics implemented in PR #12. Implementation evidence and
the remaining verification limitations are recorded below.

Implementation evidence (2026-10-18): PR #12
(`feat(financialops): add account movement history`), feature commit
`646dec8cac34947164f9078ac389557ff0a51df4`, merge commit
`19555bae91a86796792b1fa7671be1977a613e2f` on `main`. CI on the merge commit:
419 tests for the build with 0 skipped, and 80 PostgreSQL tests over 24 classes
with 0 skipped (gate reported as fully executed). The limitations documented in
ADR-22 (`docs/adr/ADR-22-account-movement-history.md`) remain in force.

## Description

A read-only projection of the confirmed, immutable financial effects on a
single account. Each returned row is one `movements` record (`DEBIT` or
`CREDIT`) belonging to the requested account. The projection adds no write, no
balance change, no financial operation and no persistence entity. It reuses the
existing authorization contracts and the existing movement/operation records.

## Requirements

- Functional: list the confirmed effects of one account over time, newest
  first, paginated, with no leakage of third-party identifiers.
- Non-functional: read-only; no locks; short-lived read transaction; safe
  errors; no new dependency on other modules' internals.

## Endpoint

```text
GET /api/v1/accounts/{accountId}/movements?limit=<n>&cursor=<opaque>
```

- Path parameter `accountId`: UUID of the account whose effects are listed.
- Query parameter `limit` (optional): page size. Default `20`, maximum `100`.
- Query parameter `cursor` (optional): opaque continuation token returned by a
  previous page's `nextCursor`.
- Header `X-Actor-Id`: identifies the actor, as in the existing endpoints; the
  current code reads it directly and has no separate authentication filter
  (declared optional by the controller binding, but authorization requires an
  authenticated actor). A missing or blank value yields an unauthenticated
  actor. No `Idempotency-Key` is required (reads are not idempotent intents).

## Response

`200 OK` with a JSON object:

```json
{
  "items": [
    {
      "movementId": "11111111-1111-1111-1111-111111111111",
      "operationId": "22222222-2222-2222-2222-222222222222",
      "operationType": "TRANSFER",
      "direction": "DEBIT",
      "amountMinorUnits": 5000,
      "currency": "USD",
      "createdAt": "2026-10-17T12:34:56Z",
      "counterparty": { "type": "TRANSFER" }
    },
    {
      "movementId": "33333333-3333-3333-3333-333333333333",
      "operationId": "44444444-4444-4444-4444-444444444444",
      "operationType": "DEPOSIT",
      "direction": "CREDIT",
      "amountMinorUnits": 10000,
      "currency": "USD",
      "createdAt": "2026-10-16T09:00:00Z",
      "counterparty": null
    }
  ],
  "nextCursor": "MTc5MjE0MTIwMDAwMDAwMDozMzMzMzMzMy0zMzMzLTMzMzMtMzMzMy0zMzMzMzMzMzMzMzM"
}
```

When there are no further pages, `nextCursor` is `null`. An account with no
confirmed effects returns `200` with `"items": []` and `"nextCursor": null`.

The example values are placeholders and contain no real account, customer or
counterparty identifiers and no personal data. The `nextCursor` above is
illustrative: it is the Base64URL of the internal `<epochMicros>:<movementId>`
form with microsecond precision (here `1792141200000000` corresponds to
`2026-10-16T09:00:00Z`), not of a seconds-precision date. The `createdAt`
values are shown with second precision for readability only: the field is a
full-precision instant and may carry sub-second precision.

## Field Semantics

- `movementId`: identifier of the immutable movement (effect) row.
- `operationId`: identifier of the originating financial operation. Both legs of
  a transfer share the same `operationId`.
- `operationType`: `DEPOSIT` | `WITHDRAWAL` | `TRANSFER`, from the operation.
- `direction`: `DEBIT` (value leaves the account) or `CREDIT` (value enters it),
  relative to the requested account.
- `amountMinorUnits`: integer amount in minor units of `currency`.
- `currency`: ISO-4217 three-letter code.
- `createdAt`: instant the effect was recorded. It is a full-precision instant
  that may carry sub-second (microsecond) precision; the JSON examples render it
  truncated to seconds for readability only.
- `counterparty`: `null` for `DEPOSIT` and `WITHDRAWAL`. For `TRANSFER`, a
  generic non-identifying descriptor; its `type` is `TRANSFER`. It NEVER
  contains the other party's account id or any personal data.

## Pagination

- Order is `created_at DESC, id DESC` (total and stable; `id` breaks ties).
- The page is the set of rows strictly older than the cursor, selected with
  `(created_at, id) < (cursor.createdAt, cursor.id)`.
- `nextCursor` encodes the last item of the returned page. Passing it as
  `cursor` returns the following page. `null` means the end.
- Cursor format: `created_at` is encoded as a signed 64-bit integer of
  microseconds since the Unix epoch (taken from the stored `TIMESTAMPTZ`,
  without lossy conversion) together with the movement UUID, serialized
  opaquely with Base64URL. On decode the implementation validates structure,
  temporal range and UUID before querying; it MUST NOT rebuild the date through
  a conversion that reduces precision. Exact microsecond precision MUST be
  preserved across PostgreSQL, JDBC and Java. The microsecond count is derived
  from the stored instant by exact conversion (no truncation, no rounding), and
  the SQL predicate compares the same instant and the same movement UUID that
  the cursor encodes.
- The cursor is opaque and is validated when decoded; a malformed cursor is a
  `400`. It is NOT encrypted and NOT signed; it MUST NOT be treated as a secret
  or as proof of authorization.
- Consistency: keyset pagination uses the immutable ordering key
  `(created_at, id)` and prevents already-traversed rows from being repeated
  solely because newer rows are inserted. It does NOT provide a consistent
  snapshot across page requests: rows committed concurrently may appear on
  later pages if their ordering key falls beyond the current cursor. The
  guarantee relies on movements being immutable (never updated or deleted).
- `limit` outside `1..100` (or non-numeric) is a `400`.

## Errors

- `200`: page returned (possibly empty).
- `400`: invalid `limit` or invalid/malformed `cursor`.
- `401`: unauthenticated actor (missing/blank `X-Actor-Id`) on an existing
  account, reusing the existing UNAUTHENTICATED mapping.
- `404`: the account does not exist OR the actor is authenticated but not
  allowed to view it; both cases are intentionally indistinguishable and MUST
  return an identical body and format, in line with the privacy rules and the
  acceptance criteria. The order of the account lookup relative to the
  authorization decision mirrors the existing account read and was confirmed
  against the implementation: the service resolves the holder first and only
  then decides.
- `500`: unexpected technical failure, with a safe body (no internals, stack
  traces, balances, or identifiers beyond what is safe).

## Privacy Rules

- The response exposes no account identifier at all, including the requested
  `accountId`; that identifier is used only as an internal query filter and is
  never returned.
- The response exposes no identifier or personal data of any counterparty.
- An authenticated caller cannot distinguish a non-existent account from one it
  may not view: both return the same `404` with an identical body. An
  unauthenticated caller receives `401` for an existing account and `404` for a
  missing one, mirroring the current read path. This inherited behavior allows
  inferring account existence for unauthenticated callers; it is documented but
  is NOT an effective privacy protection, and correcting it is a transversal
  change outside the scope of this feature.
- No internal states (e.g. DEBITED/CREDITED/PARTIAL) are exposed (ADR-10).

## Scope

Included: confirmed effects (`movements`) of one account, newest-first, with the
fields listed above.

Not included (explicitly):

- Rejected or failed attempts (they have no movement).
- Balance itself (use the existing account read) and any assertion that
  balance equals the sum of movements.
- Beneficiary alias or any counterparty identifier.
- Exports (CSV/PDF), filters by type/date/amount, retention policy, snapshot
  isolation, and read auditing.

## Dependencies

- `accounts.api.AccountLookupService.findHolderCustomerId` (holder resolution).
- `identity.AuthorizationService.decideViewAccount` (holder-or-`BANK_EMPLOYEE`).
- `financialops.core` movements/operations records.
- No `accounts.internal` access and no beneficiary-table dependency.

## Acceptance Criteria

- [ ] A holder lists their own account's confirmed effects, newest first.
- [ ] A `BANK_EMPLOYEE` actor can list any account's effects.
- [ ] An authenticated actor who is not authorized to view an existing account
      receives the same `404` response as for a non-existent account, including
      identical body and format. This equality MUST be verified against the
      actual responses of the running endpoint, not merely asserted because it
      appears in this document.
- [ ] Keyset pagination uses the immutable ordering key `(created_at, id)` and
      prevents already traversed rows from being repeated solely because newer
      rows are inserted. It does not provide a consistent snapshot across page
      requests: concurrently committed rows may appear on later pages if their
      ordering key falls beyond the current cursor.
- [ ] Cursor encodes `created_at` with exact microsecond precision (64-bit
      epoch microseconds) plus the movement UUID; precision is preserved across
      PostgreSQL, JDBC and Java, and a malformed cursor is `400`.
- [ ] `limit` default 20, maximum 100; out-of-range or malformed input is `400`.
- [ ] No response contains a third-party account id or counterparty personal
      data.
- [ ] No write, balance or audit change is produced by any read.

### Evidence (2026-10-18)

The criteria above are intentionally left unticked, following the existing
documentation style. This section records what is and what is not demonstrated.

1. Holder lists their own account's confirmed effects, newest first: demonstrated
   by `MovementHistoryIntegrationTest`
   (`holderSeesOwnConfirmedDepositWithNoCounterpartyOrAccountId`) and by the
   `created_at DESC, id DESC` ordering of the read repository, covered by
   `MovementHistoryServiceTest` and `MovementHistoryPostgresTest`.
2. `BANK_EMPLOYEE` can list any account's effects: **NOT VERIFIED END TO END**.
   The authorization contract honors the role (`StubAuthorizationService`), but
   the controller builds the actor with an empty role set and the API carries no
   role transport, so no HTTP test demonstrates that propagation. Only the
   contract level is covered, in `StubAuthorizationServiceTest`.
3. Identical `404` (body and format): demonstrated by
   `MovementHistoryIntegrationTest#unknownAccountAndAuthenticatedNonOwnerReturnByteIdentical404`,
   through the real controller, service, repository and error handler.
4. Keyset pagination without repeats from newer inserts: demonstrated by
   `MovementHistoryIntegrationTest#keysetPaginationTraversesTheStaticSetWithoutDuplicatesOrOmissions`
   and, on PostgreSQL, by `MovementHistoryPostgresTest`
   (`keysetTraversalIsExactOverAStaticSetWithTiesAndNegativeEpoch`,
   `tieIsResolvedByDescendingIdWithinThePage`). The static-set limitation stands.
5. Cursor with exact microsecond precision; malformed cursor is `400`:
   demonstrated by `MovementCursorCodecTest` (7 tests) and
   `MovementHistoryPostgresTest#cursorPreservesStoredMicrosecondsEndToEnd`.
6. `limit` default 20, maximum 100, out of range or malformed is `400`:
   demonstrated by `MovementHistoryControllerTest` and `MovementHistoryService`.
7. No third-party account id and no counterparty personal data: demonstrated by
   the integration tests asserting that the response body does not contain the
   other account's identifier.
8. No write, balance or audit change from any read: demonstrated by
   `MovementHistoryIntegrationTest#readIsNotAuditedAndDoesNotMutateBalances` and
   `MovementHistoryServiceTest#repositoryExposesNoWriteOperations`.

CI run on the merge commit `19555bae`: 419 build tests with 0 skipped, and 80
PostgreSQL tests over 24 classes with 0 skipped.

## Notes

- This document is the contract companion to ADR-22 and shares its Accepted
  status.
- The supporting index (e.g. `movements(account_id, created_at DESC, id DESC)`)
  is deferred and is NOT required for correctness (ADR-22, "Deferred Index").
- Implementation requires explicit human approval and is out of scope here.
