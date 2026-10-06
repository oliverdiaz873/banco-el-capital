# ADR-15: Stored Balance with Immutable Movements (Hybrid Financial Representation)

## Context
ADR-02 selected a Hybrid Financial Model (Operation intent plus lifecycle, Movement as
confirmed immutable effect, Balance as queryable state consistent with confirmed effects) but
left the physical balance representation OPEN (stored, derived, materialized, physically
hybrid). The Deposit increment is the first Financial Operation and forces the choice: future
withdrawals need O(1) sufficient-funds checks with a lockable serialization point, transfers
need the same on two accounts, and compensation needs an explicit effect record. A durable
rule is required because withdrawals, transfers, and reversals will all depend on it.

## Decision
Adopt the **Hybrid** physical representation for the MVP and beyond:

```text
Financial Operation
        |
Immutable Movement(s)
        |
Account Balance State
```

- Financial Operations owns operations and movements (validity, lifecycle, idempotency,
  movement creation, confirmation). It MUST NOT directly mutate the Accounts persistence
  model.
- Accounts owns the stored balance state as part of account state (existence, lifecycle,
  currency, balance value). Balance application occurs exclusively through an
  Accounts-owned contract that re-verifies operability and currency before mutating.
- Movement history is the authoritative evidence of financial effects (reconstruction,
  audit, future ledger evolution).
- Stored balance is the authoritative observable state for normal reads and future
  funds checks.
- Reconciliation between stored balance and confirmed movements (balance equals the sum
  of CONFIRMED movements) is a diagnostic and integrity mechanism, not the normal read
  path.

## Atomicity
A confirmed financial operation MUST atomically coordinate, within the same local
database transaction: operation state, movement creation, balance effect, idempotency
state, and audit event. No distributed transaction. A failure before commit leaves no
movement and no balance change; a committed CONFIRMED operation always carries its full
effects. There is no valid partially applied confirmed result.

## Consequences
- What becomes easier or more possible:
  - O(1) balance reads and future sufficient-funds checks.
  - a lockable balance holder serializing concurrent operations, including future debits.
  - immutable financial evidence and full reconstruction from movements.
  - reconciliation capability (stored state checked against confirmed effects).
  - a future ledger evolution path with the movements record as its base.
  - clear modular ownership (effects versus state).
- What becomes harder or more difficult:
  - two representations of financial state MUST be kept consistent at all times.
  - requires strict transactional discipline on every confirming path.
  - requires reconciliation tests alongside every financial feature.
  - future schema and locking design MUST preserve the balance invariant.
- Trade-offs:
  - write-path discipline and reconciliation testing now versus read-path
    aggregation cost and missing lock point on every future debit under a
    derived-only model.

## Alternatives Considered
- Stored mutable balance without immutable movements -> Rejected as primary
  representation: weaker reconstruction and integrity, no authoritative effect
  record, already rejected as primary model by ADR-02.
- Derived balance exclusively from movement aggregation -> Rejected as primary
  representation: O(history) validation reads, no natural lock point for
  concurrent debits (the account row must be locked anyway), heavier
  funds-check path for every future withdrawal and transfer.

Explicit non-decisions: exact table and column shapes, index selection, isolation
levels, locking statements, ORM mapping, API shapes, authentication mechanics, and
recovery job implementation. They belong to design and implementation within this
decision's bounds.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-01 (FinOps owns invariants, Accounts owns state, contracts only,
  local transactions), ADR-02 (fills its OPEN physical representation; the rejected
  alternatives match ADR-02's own rejections), ADR-03 (balance stays state consistent
  with confirmed effects; BLOCKED-credit policy remains a per-operation design point),
  and ADR-04 (CONFIRMED carries effects atomically; REJECTED carries none; FAILED
  carries none once determined; uncertain caller outcomes recover via idempotency query).
- Language: MUST for decided architectural rules; SHOULD for recommendations; MAY for
  future possibilities.
