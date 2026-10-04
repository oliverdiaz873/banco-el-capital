# ADR-10: API Architecture

## Context
The system needs an explicit versioned API capable of representing financial operations, results, states, later queries, idempotency, safe errors, and correlation/determinability, consistently with ADR-04 lifecycle, ADR-05 transfers, and ADR-06 determinability.

## Decision
Adopt REST as the architectural API direction with versioning and explicit contracts. The API MUST represent operations, results, states, later queries, idempotency, safe errors, and correlation/determinability. It MUST respect ADR-06 same logical identity without duplicate effects, retries converging to the same result, queryable prior results, and incompatible payload as conflict. It MUST NOT expose internal concepts such as DEBITED, CREDITED, or PARTIAL, and MUST respect ADR-05 one operation plus two linked movements and ADR-04 PENDING/CONFIRMED/REJECTED/FAILED. Errors MUST remain safe and MUST NOT unnecessarily reveal account existence, balances, personal data, internals, infrastructure, or stack traces.

## Consequences
- What becomes easier: universal versioned contracts; queryable idempotent operations; safe evolution.
- What becomes harder: concrete endpoints, DTOs, pagination, status codes, and framework choices deferred.
- Trade-offs: REST direction now versus GraphQL/gRPC flexibility that is unnecessary for this monolith stage.

No concrete endpoints, DTOs, OpenAPI, concrete HTTP status codes, concrete pagination, Java classes, controllers, web framework, or authentication endpoints designed here. GraphQL/gRPC mentioned only as considered and deferred/discarded alternatives.

## Alternatives Considered
- REST with versioning -> Selected as direction: explicit, universal, evolvable.
- GraphQL -> Deferred/discarded for now: unnecessary flexibility and complexity.
- gRPC -> Deferred/discarded for now: unnecessary for in-process monolith clients.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-04, ADR-05, and ADR-06 at creation time. Domain remains source of truth over API shape. No premature implementation.
