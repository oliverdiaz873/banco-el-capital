# ADR-13: Testing Strategy

## Context
The banking core must verify financial invariants, lifecycle determinability, transfer atomicity, idempotency, concurrency, authorization, account states, reversal, and recovery, consistently with the risk-based workflow and without mandating every test level on day one.

## Decision
Adopt a risk-and-invariant-based testing strategy. Map each invariant to its proportional level: unit for lifecycle, funds, and state rules; integration for module contracts and real-database behavior; API and contract tests for versioning, safe errors, and idempotent queryable results; E2E only for critical journeys with Playwright as established tooling; dedicated concurrency, invariant, failure and recovery, and security tests for funds races, duplicate effects, partial transfers, authorization, blocked and closed states, compensation, and restart and uncertain outcomes. Testcontainers remains a hypothesis for real-database integration, not a premature decision. No framework, coverage percentage, environment, or CI implementation is mandated here.

## Consequences
- What becomes easier: regressible invariants; testable concurrency and recovery; proportional E2E scope.
- What becomes harder: test data, seeds, environments, and parallelization still required; concurrency tests need discipline.
- Trade-offs: invariant coverage now versus exhaustive levels that would slow early implementation.

Alternatives: unit-only was rejected as insufficient for money; exhaustive all-levels-day-one was rejected as premature obligation; risk-based pyramid was selected. No JUnit, Mockito, Testcontainers mandate, CI system, or numeric thresholds decided here.

## Alternatives Considered
- Unit only -> Rejected: cannot protect money movement and concurrency.
- All levels mandatory day one -> Rejected: premature cost without proportional value.
- Risk-and-invariant pyramid -> Selected: proportional, evolvable, workflow-compatible.
- Testcontainers as mandatory now -> Deferred as hypothesis: valuable but not yet decided.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-02 financial effects, ADR-04 lifecycle and recovery, ADR-05 one-operation-two-movements, ADR-06 determinability, ADR-07 concurrency units, ADR-10 versioned safe API, and ADR-11 history and audit separation at creation time. No premature test infrastructure.
