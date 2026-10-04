# ADR-07: Concurrency and Financial Consistency

## Context
The financial core must handle concurrent withdrawals and transfers on the same account, operations on different accounts, races, funds validation under concurrency, transfer atomicity, idempotency interaction, double-spending prevention, and financial-state consistency, within a Modular Monolith with local transactions and without distributed machinery.

## Decision
Validation of a relevant financial condition and application of its corresponding effect MUST belong to one consistency unit that prevents concurrent operations from producing an invalid financial state. The system MUST maintain no partially CONFIRMED transfer, no double spending, and no confirmation of operations that violate financial invariants.

Conflict classification is NOT automatically REJECTED nor automatically FAILED. A deterministically resolvable conflict MAY end in an appropriate business response, while technical uncertainty MAY require FAILED and recovery per ADR-04. A partially or financially inconsistent confirmation MUST never occur.

Conceptual approaches such as serialization, optimistic concurrency, and pessimistic concurrency were compared, but no concrete locks, isolation level, Redis, distributed locks, or other physical mechanism is selected here. This decision establishes the invariants and requirements future implementation MUST satisfy.

## Consequences
- What becomes easier: explicit concurrency invariants; funds and atomicity protection; coherent interaction with idempotency and lifecycle; testable expectations.
- What becomes harder: physical serialization strategy still required; throughput and contention management deferred; recovery discipline required.
- Trade-offs: invariant strictness now versus mechanism flexibility later; simplicity of a conceptual unit versus future tuning complexity.

No Redis, Kafka, distributed locks, microservices, distributed transactions, concrete isolation level, physical locking mechanism, ORM, or SQL decided here. Compatible with ADR-02, ADR-03, ADR-04, ADR-05, and ADR-06.

## Alternatives Considered
- Conceptual serialization unit -> Selected as invariant: validation plus effect in one unit.
- Optimistic concurrency only -> Deferred as mechanism: viable but not selected here.
- Pessimistic concurrency only -> Deferred as mechanism: viable but not selected here.
- Distributed locks/queues -> Rejected for MVP: unnecessary distribution.
- Automatic conflict equals REJECTED -> Rejected: conflates business and technical outcomes.
- Automatic conflict equals FAILED -> Rejected: overstates technical failure.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Validation: consistent with ADR-01 in-process local transactions, ADR-02 Operation/Movement/Balance, ADR-03 Account operability, ADR-04 lifecycle and FAILED recovery, ADR-05 one-operation-two-movements, and ADR-06 same-intent determinism at creation time.
