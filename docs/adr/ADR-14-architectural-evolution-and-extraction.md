# ADR-14: Architectural Evolution and Module Extraction Criteria

## Context
The Modular Monolith must mature first and extract selectively only on real technical, operational, scalability, resilience, ownership, security, or deployment evidence, without designing microservices now and without letting eventually microservices become an excuse to distribute prematurely.

## Decision
Keep Modular Monolith first, then mature, then extract selectively on evidence. Maintain contracts, logically separated data without cross-module joins, local transactions, and correlated observability. Extract peripheral capabilities before the financial core; the financial core stays among the last candidates. Objective extraction signals: independent scaling, availability isolation, ownership, deployment independence, security boundaries, operational requirements, substantially different workloads, independent evolution, and mature consistency boundaries. No numeric thresholds or exact extraction order are fixed here.

## Consequences
- What becomes easier: evolution without rewrite; objective extraction decisions; peripheral-first risk control.
- What becomes harder: boundary discipline must be active; premature distribution temptation must be resisted; data separation requires care.
- Trade-offs: discipline now versus costly future decoupling if coupling accumulates.

No Kubernetes, gateway, saga, per-service database, distributed events, exact thresholds, Java, Spring, PostgreSQL, Maven/Gradle, Docker, secrets, or concrete package structure decided here. Those belong to Setup/Implementation unless later impact justifies a dedicated technological ADR. No ADR-15 is created now.

## Alternatives Considered
- Premature extraction -> Rejected: distributed cost without evidence.
- Never extract -> Rejected: denies justified evolution.
- Signal-based selective extraction -> Selected: Modular Monolith first, mature, evidence, selective extraction.
- Core-first extraction -> Rejected: highest financial risk should move last.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-01 boundaries, ADR-08 relational direction, ADR-11 transverse audit, and ADR-12 correlated observability at creation time. Rule: Modular Monolith first, maturation, evidence, selective extraction.
