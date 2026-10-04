# ADR-04: Financial Operation Lifecycle

## Context
ADR-01 assigns financial invariants to Financial Operations and Account lifecycle to Accounts, with in-process contracts. ADR-02 defines Operation as intent plus lifecycle and Movement as confirmed immutable effect. ADR-03 defines Account, holders, states, currency, product, and balance as state consistent with confirmed effects. We must define the Operation lifecycle, business versus technical outcomes, effects production, idempotency, timeout/disconnection, and recovery determinability, without persistence, API, locking, queue, or infrastructure decisions.

## Decision
Adopt a four-state Financial Operation lifecycle: PENDING, CONFIRMED, REJECTED, FAILED, with explicit recovery/reconciliation capability and no formal UNKNOWN state in MVP.

PENDING is unresolved. It has no confirmed financial effects, MAY resolve later, and a retry with the same logical identity MUST NOT create another operation.

CONFIRMED is a confirmed financial operation. It has its corresponding effects; in a transfer, debit plus credit linked to the same operation. It is immutable after confirmation. A retry reuses the result. Later correction uses a new compensation operation.

REJECTED is deterministic business-rule rejection, for example insufficient funds, non-operable account, unauthorized actor, invalid destination, invalid beneficiary, or invalid amount. REJECTED is NOT a technical failure and produces no confirmed financial effects.

FAILED is a technical/operational failure that prevents normal confirmation. FAILED means the operation is NOT financially confirmed. We do NOT assert FAILED universally means zero physical effects, because a technically uncertain situation MAY require recovery/reconciliation. Therefore it is not automatically treated as CONFIRMED, it MAY require recovery, uncertain situations MUST resolve explicitly and traceably, and recovery MUST never duplicate effects.

Normal transitions: PENDING -> CONFIRMED, PENDING -> REJECTED, PENDING -> FAILED. No formal UNKNOWN in MVP. No arbitrary silent FAILED -> CONFIRMED lifecycle mutation. If a FAILED operation needs recovery, an explicit traceable resolution/reconciliation MUST determine the real outcome, ensuring any financial effect applies at most once. Reconciliation is conceptual; whether automatic, manual, or hybrid is OPEN.

Invariants: a CONFIRMED operation has a valid financial result; REJECTED has no confirmed effects; FAILED is not automatically confirmed; one logical operation never executes twice; retry keeps the same logical identity; CONFIRMED is immutable; timeout does not imply blind re-execution; the result MUST be determinable or recoverable; a CONFIRMED transfer has its corresponding effects; correction uses a new related operation rather than silently mutating the prior one.

Movement consistency with ADR-02: PENDING has no confirmed Movement; REJECTED has none; CONFIRMED has its Movement(s); FAILED is not assumed to have confirmed effects; confirmed movements are immutable.

Idempotency: same logical operation has the same identity; same idempotency identity MUST NOT duplicate execution; PENDING retry returns the same operation; terminal retry returns/reuses the result; same identity with different payload is a conceptual conflict. Implementation remains OPEN.

Timeout/disconnection: server-confirmed plus lost response stays CONFIRMED with no duplicate on retry and later queryable result; unknown processing MUST be determined through query/recovery without blind re-execution; processing failures MUST avoid double effect, false CONFIRMED, traceability loss, or impossible financial state.

Attempts: Financial Operation is the logical operation and lifecycle; Operation Attempt is an attempt to execute/transmit the same logical operation. A retry is a new attempt of the same operation, not a new financial operation. The conceptual model MUST allow reconstructing at least attempt number, moment, result/state, and Operation relationship. No tables or classes designed here.

Failure coverage: business rejection, insufficient funds, blocked account, database/technical failure, timeout, disconnect after confirmation, retry, concurrent same-idempotency requests, failure after validation, transfer failure, process restart, and technically uncertain results are all mapped to PENDING/REJECTED/FAILED/CONFIRMED plus explicit recovery as above, without implementation.

## Consequences
- What becomes easier: explicit lifecycle; clear business/technical separation; determinability; idempotency; traceability; ADR-02 coherence; MVP simplicity.
- What becomes harder: recovery/reconciliation must be designed later; FAILED requires discipline not to assume effects; idempotency implementation remains pending.
- Trade-offs: four states plus explicit recovery avoids premature UNKNOWN/CANCELLED/EXPIRED states while preserving a conceptual path for uncertain outcomes.

Alternatives: four-state lifecycle was selected. Four states plus formal UNKNOWN, separate FAILED versus UNCERTAIN, and terminal FAILED plus external reconciliation were considered; the selected variant keeps MVP simplicity with explicit recovery capability. No CQRS, event sourcing, locks, queues, or distributed machinery introduced.

Explicit non-decisions: PostgreSQL, ORM, SQL schema, locking, isolation levels, Redis, Kafka, queues, Event Sourcing, CQRS, microservices, distributed transactions, API endpoints, HTTP status codes, Java classes, recovery implementation, reconciliation jobs, retention policy, and exact payload hashing/conflict mechanism.

## Alternatives Considered
- Four-state lifecycle -> Selected: explicit, simple, idempotent, traceable, coherent with ADR-02.
- Four states plus formal UNKNOWN -> Rejected for MVP: premature state without proven need.
- Separate FAILED versus UNCERTAIN -> Deferred: conceptually valid, but explicit recovery on FAILED is sufficient now.
- Terminal FAILED plus external reconciliation only -> Rejected as sole model: recovery MUST be part of the lifecycle semantics, even if implementation is external.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Financial Operations owns this lifecycle; Accounts remains owner of Account lifecycle per ADR-01/ADR-03; Movement only represents confirmed effects per ADR-02; REJECTED is distinct from FAILED; FAILED is not automatically zero physical effects; no formal UNKNOWN in MVP; idempotency, retry, and timeout behavior preserved; no premature infrastructure.
- Validation: consistent with ADR-01, ADR-02, and ADR-03 at creation time.
