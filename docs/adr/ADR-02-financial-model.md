# ADR-02: Hybrid Financial Model

## Context
ADR-01 established Banco El Capital as a Modular Monolith with Financial Operations as owner of financial invariants and Accounts as owner of lifecycle/state and Account-Holder relationship, with in-process communication and local transactions.

We must decide how to represent financial operations, effects, movements, balance, consistency, traceability, transfers, reversals, concurrency, and reconstruction. The Conceptual Financial Integrity Validation (PASS WITH OPEN IMPLEMENTATION DETAILS) confirmed the Hybrid direction preserves the required invariants.

## Decision
Banco El Capital MUST use a **Hybrid Financial Model** in which Financial Operation represents intent and lifecycle, Movement represents confirmed immutable financial effect, and the system maintains queryable financial state consistent with confirmed effects.

```text
Financial Operation
        |
        |- lifecycle
        |- idempotency
        |- result
                |
                v
        Confirmed Financial Effect
                |
                v
           Movement(s)
                |
                v
       Observable Financial State
```

For transfers:

```text
Transfer Operation
       |
       |- Debit Movement -> Source
       |
       |- Credit Movement -> Destination
```

A reversal MUST NOT modify the past:

```text
Original Operation
       |
       |- Compensation Operation
```

### Financial Operation
A Financial Operation is a financial intent and its lifecycle. It conceptually includes stable operation identity, actor, operation type, amount + currency, relevant accounts, idempotency identity, timestamps, attempts, and result/state: PENDING -> CONFIRMED or REJECTED or FAILED.

REJECTED is a deterministic business-rule outcome (for example insufficient funds, account not operable, unauthorized operation, invalid business condition). FAILED is a technical/operational failure that prevents determining or completing the result correctly and MAY require recovery. REJECTED and FAILED MUST NOT be treated as synonyms.

### Movement
Movement is a confirmed immutable financial effect. It conceptually contains originating Financial Operation, Account, debit/credit direction, amount, currency, and correlation/reference. Once confirmed it is not modified, not deleted, and not used to overwrite history. Correction happens through a new compensatory operation/movement. Immutability is a consequence of this selected model, not a universal property independent of it.

### Relationship Between Operation and Movement
```text
Financial Operation
        |
        |- confirmed financial effect
                    |
                    |- Movement(s)
```
A CONFIRMED operation MUST produce its corresponding effects. REJECTED produces no confirmed effect. PENDING produces no confirmed effect. FAILED MUST NOT be automatically considered a confirmed effect. No Movement is a confirmed financial effect until the operation reaches the corresponding state; uncertain technical states MAY require later recovery/reconciliation. Recovery is left to later decisions.

### Balance / Financial State
Balance is queryable observable financial state that MUST remain consistent with confirmed financial effects. We do NOT establish balance = sum(all movements) as a mandatory physical rule. Physical representation (stored, derived, materialized, physically hybrid) remains OPEN. Accounting / available / held are NOT introduced as three separate balances. Available funds MAY exist as an operation-validation requirement, with physical representation OPEN.

### Transfer
Transfer = 1 Financial Operation + 2 linked Movements. Both movements belong to the same operation, represent the same transfer, are correlated, and MUST confirm as one financial unit with no confirmed partially applied final result. We MUST NOT use an operation that simply updates two balances as the conceptual model.

### Atomicity
A CONFIRMED transfer MUST produce Debit source + Credit destination as one coherent effect. A CONFIRMED result with only one side is invalid. Concrete atomicity implementation is out of scope (no PostgreSQL, isolation, locks, optimistic/pessimistic locking, or distributed transactions decided here).

### Concurrency
Concurrent withdrawals or transfers competing for the same funds MAY confirm only those preserving financial invariants. Two requests with same intent and idempotency identity are one logical operation: same business intent + same idempotency identity -> same operation/result, with no new financial effect. Deduplication and concurrency implementation remain OPEN.

### Idempotency
The model includes stable operation identity, idempotency identity, retry, and queryable result. A retry of the same intent creates neither another Operation nor new Movements. HTTP header, database constraint, Redis, and same-key-different-payload behavior remain OPEN for a later ADR.

### Reversal / Compensation
Correction MUST be Compensation, not UPDATE/DELETE/overwrite of the original movement or historic result. Compensation keeps an explicit link to the original. Who MAY authorize it, which operations are compensable, timing, and retention remain OPEN.

### Reconstruction
A CONFIRMED operation MUST be conceptually reconstructible through Operation, actor, amount, currency, accounts, state, Movement(s), correlation/reference, timestamps, and compensation link when present. Financial history, audit records, and technical logs MUST NOT be confused; detailed Audit/Trace responsibilities remain under ADR-01 and future ADRs.

## Consequences
- What becomes easier or more possible: stronger integrity model; explicit intent versus effect; immutable confirmed effects; reconstructible history; safer retries; explicit transfer atomicity; compensation without destroying history; Modular Monolith compatibility; conceptual path for future evolution toward more sophisticated accounting/ledger capabilities if justified.
- What becomes harder or more difficult: greater conceptual complexity than mutable balance + history; strict discipline around confirmed effects; preserving state-versus-effects consistency; concurrency implementation remains important; persistence design MUST respect the model.
- Trade-offs: discipline now versus weaker guarantees with mutable model; sufficient power for MVP without premature double-entry, event sourcing, or distributed machinery.

What Hybrid is NOT: it is NOT always sum-of-movements; NOT double-entry; NOT Event Sourcing; it does NOT guarantee concurrency automatically; it does NOT make future double-entry migration trivial.

## Alternatives Considered
- A - Mutable Balance + History -> Rejected as primary model: weaker reconstruction/integrity and greater risk around concurrent updates and duplicates.
- B - Append-only Ledger -> Strong but more ledger-centric than MVP needs; MAY serve as conceptual evolution.
- C - Double-entry Accounting -> Very strong for formal accounting and future banking, but excessive for current MVP; possible future evolution if justified.
- D - Event Sourcing -> Rejected for now: event evolution, projections, replay, and operational complexity without current domain need. Not to be confused with a financial ledger.
- E - Hybrid Financial Model -> Selected: explicit lifecycle, immutable confirmed effects, queryable state, transfer traceability, idempotency compatibility, concurrency invariants, compensation, and evolution without premature event sourcing or full accounting.

Explicit non-decisions: PostgreSQL, ORM, SQL schema, stored versus derived physical balance, isolation levels, locking, Redis, Kafka, queues, CQRS, Event Sourcing, full Double-entry, microservices, distributed transactions. They belong to later ADRs or implementation.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Validation basis: Conceptual Financial Integrity Validation, PASS WITH OPEN IMPLEMENTATION DETAILS, covering concurrent withdrawals/transfers, timeout+retry, disconnect-after-confirm, concurrent same-key, business rejection, technical failure, partial transfer failure, and compensation.
- Precision: no Movement is confirmed until its operation reaches the corresponding state; uncertain FAILED states MAY need recovery rather than an absolute zero-movement rule.
- Consistency with ADR-01 verified at creation: respects Modular Monolith, FinOps invariant ownership, Accounts lifecycle ownership, in-process contracts, and local transactions; introduces no premature infrastructure.
- Operation versus Movement is explicit; Transfer is one operation plus two linked movements; Balance stays physically OPEN; REJECTED versus FAILED remain distinct; compensation preserves original history.
