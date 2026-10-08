# ADR-01: Modular Monolith and Module Boundaries

## Context
Banco El Capital is a long-term banking engineering project for advanced learning and professional demonstration.

It requires:
- financial integrity;
- consistency;
- concurrency;
- idempotency;
- determinability after failures;
- early auditability and traceability;
- security;
- maintainability;
- testability;
- architectural evolution without introducing premature distributed complexity.

We need an initial architecture with real boundaries and local transactions that can evolve toward distributed systems only when sufficient technical or operational justification exists.

## Decision
Banco El Capital MUST start as a **Modular Monolith** with real architectural boundaries defined by responsibilities, ownership, invariants, and consistency boundaries.

Boundaries MUST NOT exist only as folder organization.

### Domain Modules

#### Identity / Customers
Responsible for:
- Customer;
- conceptual identity;
- Holder identity;
- identity rules under its responsibility.

It does NOT own financial logic.

It does NOT own the Account-Holder ownership relationship.

#### Accounts
Responsible for:
- Account;
- lifecycle / state;
- Account-Holder relationship;
- product reference;
- account operability.

It does NOT own global financial logic.

#### Financial Operations
Responsible for:
- Financial Operations;
- deposits;
- withdrawals;
- transfers as use cases;
- financial invariants;
- idempotency;
- financial consistency coordination.

The initial concentration of these responsibilities is accepted because Financial Operations is the natural owner of financial invariants.

The future God Module risk MUST be documented explicitly (see Notes).

#### Beneficiaries
Responsible for:
- Beneficiaries;
- relationship with Customer;
- references to authorized destinations;
- Account resolution when needed.

It does NOT own Accounts.

### Audit / Trace as cross-cutting capability
Audit / Trace MUST be treated as a cross-cutting capability, not as a fifth domain module.

It MUST:
- provide traceability capability;
- allow other modules to produce evidence through contracts;
- stay out of financial rules.

Not decided yet:
- physical storage;
- data schema;
- technology;
- logging framework.

### Dependency Rules

Allowed:
- Financial Operations -> Accounts
- Financial Operations -> Identity / Customers
- Financial Operations -> Audit / Trace
- Beneficiaries -> Customers
- Beneficiaries -> Accounts for necessary resolution / query

Prohibited:
- Accounts -> Financial Operations to delegate financial logic;
- any module -> direct access to another module internal state;
- any module -> direct access to another module database;
- any module -> direct modification of state owned by another module;
- any module -> bypassing the owner module contract.

No concrete Java interfaces are created by this ADR.

### Communication
The initial architecture MUST use:
- internal contracts;
- synchronous communication;
- in-process communication.

It MUST NOT introduce:
- HTTP between modules;
- Kafka;
- Redis;
- distributed event bus.

Domain Events may exist as domain concepts, but this does NOT imply distributed infrastructure.

### Transactions / Consistency
The Modular Monolith MAY use:
- local transactions;
- strong consistency when needed;
- coordination within the same process.

It MUST NOT introduce distributed transactions.

Not decided yet:
- isolation levels;
- locking strategy;
- PostgreSQL;
- ORM;
- SQL;
- physical persistence strategy.

### Evolution
Extractability is not obligation to extract.

A future module extraction will require sufficient evidence, for example:
- independent scaling;
- availability isolation;
- ownership;
- deployment independence;
- security boundaries;
- operational requirements;
- substantially different workloads;
- independent evolution;
- mature consistency boundaries.

No decision is made now about when an extraction will happen.

### Physical Representation (Addendum)
The logical modularity defined above also has an explicit physical feature-based representation
(see ADR-17). Boundaries do not rely on packages alone: permitted dependencies are expressed
through module contracts (`api/`), implementations stay encapsulated (`internal/`), and HTTP
boundaries live with their own feature (`web/`). These are encapsulation mechanisms, not a
mandatory template: a package exists only where it adds real encapsulation. ArchUnit verifies
the boundaries at build time.

## Consequences
- What becomes easier or more possible:
  - explicit boundaries;
  - local transactions;
  - lower operational complexity;
  - better testability;
  - progressive evolution;
  - possible future extraction.
- What becomes harder or more difficult:
  - requires architectural discipline;
  - incorrect dependencies can degrade the architecture;
  - Financial Operations may grow too large;
  - boundaries must be actively maintained.
- Trade-offs:
  - discipline cost now versus distributed complexity cost later;
  - concentration of financial invariants in one owner versus premature artificial split.

## Alternatives Considered
- Coupled / Traditional Monolith -> Rejected: high coupling, weak boundaries, harder evolution.
- Modular Monolith -> Selected: clear boundaries, local transactions, lower operational complexity, better testability, progressive evolution, future extraction possibility.
- Microservices from Day One -> Rejected: distributed complexity, additional consistency problems, higher operational load, higher testing complexity, no current justification.

## Status
Proposed - being formalized, awaiting final approval. NOT marked as Accepted before final review.

## Notes
- God Module risk: Financial Operations initially concentrates several invariants as natural owner. Future split signals that could justify separation: excessive responsibilities, independent change cycles, different ownership, different scale needs, excessively coupled tests, clearly separable consistency boundaries. Evolution will require evidence.
- Explicitly out of scope for ADR-01 (remain open): financial model; balance model; stored / derived / hybrid physical representation; double-entry; persistence engine; PostgreSQL; authentication implementation; authorization implementation; REST / API contracts; transfer representation; reversal; audit storage; Kafka; Redis; Kubernetes; cloud; distributed events; microservice extraction; Product details; detailed account lifecycle transitions.
- Future stack context (already decided elsewhere, NOT part of this ADR, NOT installed/configured here): Jest for testing; Playwright for E2E; Tailwind CSS for frontend styling.
- Language: MUST for decided architectural rules; SHOULD for recommendations; MAY for future possibilities.
- Consistency check at creation time: boundaries allow approved Requirements; no contradiction detected with Architecture Discovery or Domain Modeling Discovery; no unnecessary infrastructure introduced; boundaries strong enough to protect financial invariants while allowing future evolution without premature microservices.
