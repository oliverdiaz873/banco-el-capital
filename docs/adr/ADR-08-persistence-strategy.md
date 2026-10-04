# ADR-08: Persistence Strategy

## Context
The banking core needs durable, consistent, recoverable persistence compatible with Operation/Movement/Balance, Modular Monolith boundaries, local transactions, idempotency, concurrency invariants, auditability, migrations, testing, and future evolution, without premature tables, ORM, or infrastructure decisions.

## Decision
Adopt relational persistence as the architectural direction for the banking core because it supports financial integrity, local transactions, constraints, entity relationships, consistency, concurrency, migrations, and testability.

PostgreSQL is a strong candidate but is NOT made mandatory here. The architectural decision (relational persistence) is kept separate from the concrete technological decision (PostgreSQL), which remains OPEN.

Persistence MUST conceptually support a consistent Operation plus Movements unit, idempotent identity uniqueness, operation reconstruction and determinability, referential integrity, immutable confirmed effects, migration-based evolution, and durable recovery.

## Consequences
- What becomes easier: ACID local units; constraints and referential integrity; concurrent consistency; migration evolution; durable recovery; testability with real databases.
- What becomes harder: physical modeling, index tuning, pooling, operations, and backup/restore still required later.
- Trade-offs: relational discipline now versus schemaless flexibility that would weaken financial guarantees; direction now versus engine lock-in avoided.

Alternatives: relational was selected as direction; document database and other stores were considered only where technically relevant and deferred/rejected for the core because of weaker transactional, constraint, consistency, and auditability guarantees for this domain. No tables, columns, SQL, physical schemas, ORM, Java entities, indexes, connection pools, Docker, infrastructure, or PostgreSQL configuration designed here.

## Alternatives Considered
- Relational database -> Selected as direction: ACID, constraints, relationships, migrations, testability.
- Document database -> Not selected for core: weaker transactions/constraints/auditability for financial invariants.
- Other stores -> Deferred unless technically relevant: no current justification.
- PostgreSQL as mandatory now -> Deferred: strong candidate, kept separate from architectural direction.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with Modular Monolith, Operation/Movement/Balance, Account lifecycle, PENDING/CONFIRMED/REJECTED/FAILED with explicit recovery, Transfer one-operation-two-movements, idempotency determinism, and concurrency units at creation time. Engine choice, physical balance representation, indexes, retention, and backup/restore remain OPEN.
