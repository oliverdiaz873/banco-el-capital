# ADR-05: Transfer Model

## Context
ADR-01 assigns financial coordination to Financial Operations within a Modular Monolith. ADR-02 defines Operation as intent plus lifecycle and Movement as confirmed immutable effect. ADR-03 defines Account lifecycle, holders, currency, product, and balance consistency. ADR-04 defines PENDING/CONFIRMED/REJECTED/FAILED with explicit recovery and idempotency. We must define Transfer as a financial use case without separate infrastructure, persistence, API, locking, or distributed decisions.

## Decision
Adopt one Financial Operation of type TRANSFER plus two linked Movements. Conceptually TRANSFER Operation has DEBIT Movement on source and CREDIT Movement on destination, both belonging to the same operation and representing its complete effect. Transfer is NOT a separate architectural module. We MUST NOT create two independent Financial Operations for debit and credit.

Transfer uses exactly the ADR-04 lifecycle: PENDING, CONFIRMED, REJECTED, FAILED. No DEBITED, CREDITED, or PARTIALLY_COMPLETED states. A CONFIRMED transfer represents the complete effect.

Source MUST satisfy before confirmation: source exists, source operable, actor authorized, amount greater than zero, compatible currency, sufficient funds, and applicable Product rules. BLOCKED and CLOSED are NOT operable transfer sources. Accounts/Product owns operability rules; Financial Operations coordinates the financial operation.

Destination MUST satisfy existence, state, receive capability under current rules, compatible currency, and Product rules. CLOSED MUST NOT receive a new transfer. BLOCKED as destination credit reception remains OPEN per ADR-03 and MUST NOT be invented here.

Authorization keeps authenticated user, Customer, Holder, authorized operator, beneficiary, and account owner distinct. Transfer requires authorization on source. We do NOT assume authenticated user equals holder. Identity/Accounts provides the authorization basis; Financial Operations coordinates and applies it. No RBAC, OAuth/OIDC, MFA, tokens, or HTTP permissions designed here.

Own/beneficiary destinations: a transfer MAY target an own/authorized account or an account represented through a valid Beneficiary. Beneficiary is NOT Account; it is a reference owned by Beneficiaries and resolved/validated at operation time. When a beneficiary is used, enough reference MUST be retained to reconstruct which beneficiary was used. Physical storage remains OPEN. No external transfers, ACH, cards, rails, or bank integrations. Direct destination versus beneficiary-only remains an open question if not closed and is not presented as a technical decision.

Atomicity is a central invariant: a CONFIRMED transfer has debit source plus credit destination. A CONFIRMED transfer with only one side MUST NOT exist. If the coherent set cannot be produced, the operation MUST NOT remain as a partially confirmed transfer. This model alone does not guarantee technical atomicity; implementation through DB transactions, locking, isolation, serialization, or distributed transactions is explicitly not decided here.

Movements: source has account source, direction DEBIT, amount, currency, originating operation, and correlation/reference; destination has account destination, direction CREDIT, and the same linkage. Both belong to the same Transfer Operation and confirmed movements are immutable per ADR-02. Formal double-entry accounting is NOT imposed.

Funds: transfers MUST preserve the available-funds rule. Conceptually source 100 with transfer 80 MAY confirm if all other rules pass, while transfer 120 is REJECTED for insufficient funds. Concurrent transfers from the same account MAY confirm only when their joint result preserves financial integrity. Technical enforcement remains OPEN.

Currency MVP: amount greater than zero, explicit currency, compatible source/destination, single-currency model, no FX/conversions, no floats as conceptual monetary representation. Multi-currency/FX is future evolution.

Idempotency per ADR-04: same logical operation plus same identity never duplicates; PENDING retry returns the same operation; terminal retry returns the same result; incompatible payload with same identity is a conceptual conflict. Implementation remains OPEN for ADR-06.

Failures: missing source/destination, blocked source, closed destination, unauthorized actor, invalid amount, insufficient funds, incompatible currency, invalid beneficiary are REJECTED. Technical failure before effects follows FAILED/recovery per ADR-04. Failure during transfer MUST never yield partially CONFIRMED. Timeout has queryable result without blind re-execution. Disconnect after confirmation stays queryable CONFIRMED. Retry returns same result. Concurrent transfers preserve integrity. Restart uses recovery. Uncertain results use explicit reconciliation. Recovery implementation remains OPEN.

Traceability: a transfer MUST be reconstructible through operation id, source, destination, amount, currency, actor, beneficiary if used, timestamp, result/state, linked movements, correlation/reference, and idempotency identity. Transaction history, audit, and application logs stay separated. No audit schema designed here.

Compensation/reversal: a CONFIRMED transfer is never edited to undo it. Reversal uses a new compensation Financial Operation with inverse effects and a link to the original, which remains intact. Compensation authorization remains OPEN.

## Consequences
- What becomes easier: clear logical unit; atomicity as domain invariant; traceability; idempotency; ADR-02/ADR-04 coherence; no premature distribution; future evolution.
- What becomes harder: atomicity implementation pending; concrete concurrency strategy pending; BLOCKED-destination rule pending; detailed authorization pending; reversal pending later ADR.
- Trade-offs: conceptual atomicity now versus technical enforcement later; single-operation simplicity versus two-operation flexibility that would harm reconciliation.

Alternatives B was selected. A two independent operations was rejected for harming atomicity, idempotency, and reconciliation. C formal double-entry was not selected for MVP but MAY be evaluated later if accounting requirements justify it. D event-based/distributed transfer was not selected for MVP because of distributed complexity and premature eventual consistency. B does NOT automatically guarantee technical atomicity and its evolution to double-entry is NOT claimed trivial.

Invariants: transfer is one logical Financial Operation; CONFIRMED implies corresponding debit plus credit; no partially applied CONFIRMED transfer; source authorized and operable; destination valid and compatible; amount greater than zero; compatible MVP currency; funds rule respected; retry never duplicates; concurrency preserves integrity; confirmed movements immutable; confirmed transfer reconstructible; reversal uses a new operation; transfer needs no separate architectural module.

Explicit non-decisions: PostgreSQL, ORM, SQL schema, DB transactions, isolation levels, locks, optimistic/pessimistic concurrency, Redis, Kafka, queues, distributed transactions, microservices, CQRS, Event Sourcing, formal Double-entry, FX, external transfers, ACH, cards, payment rails, fraud detection, fees, overdraft, concrete limits, API endpoints, HTTP status codes, and Java classes.

## Alternatives Considered
- A Two independent operations -> Rejected: weakens atomicity, idempotency, and reconciliation.
- B One Operation plus two Movements -> Selected: logical unity, invariant atomicity, traceability, ADR-02 coherence, no premature distribution or accounting.
- C Formal double-entry -> Deferred: strong accounting but excessive for MVP.
- D Event-based/distributed transfer -> Rejected for MVP: distributed complexity and eventual consistency without justification.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Consistency at creation: Transfer is not a separate module; 1 Operation plus 2 Movements; atomicity is conceptual not automatic technical guarantee; source/destination rules respected; authorization separated; Beneficiary is not Account; BLOCKED destination stays OPEN; idempotency and ADR-04 lifecycle preserved; compensation model preserved; no premature infrastructure.
- Validation: consistent with ADR-01, ADR-02, ADR-03, and ADR-04 at creation time.
