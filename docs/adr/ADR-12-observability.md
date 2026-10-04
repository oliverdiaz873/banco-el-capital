# ADR-12: Observability

## Context
The Modular Monolith needs operability and diagnosis distinct from financial auditability, covering failure detection, technical investigation, performance, and recovery understanding, without premature distributed tracing or vendor lock-in.

## Decision
Establish minimum observability for the Modular Monolith and explicitly distinguish Observability from Auditability. Observability serves operating, diagnosing, detecting failures, investigating technical issues, assessing performance, and understanding recovery. Auditability serves reconstructing financial facts and proving who did what with traceability.

MVP conceptual minimum: structured logs with enough correlation to diagnose operations without unnecessary sensitive data; conceptual metrics covering at least operation rate, latency, errors, failures, saturation, and relevant financial-operation behavior; correlation across monolith layers using conceptual operation id, correlation id, and request/interaction id when applicable. Credentials, tokens, sensitive personal data, balances, and other sensitive financial information MUST NOT be logged unnecessarily. Future evolution toward distributed tracing, APM, dashboards, alerting, SLOs, and distributed observability for separated services remains OPEN.

## Consequences
- What becomes easier: diagnosable monolith; correlated operations; failure and recovery insight.
- What becomes harder: tooling, sampling, dashboards, and SLOs still required later; sensitive-data discipline required.
- Trade-offs: minimal correlation now versus full distributed observability later only if services separate.

No vendor, agent, specific tool, mandatory OpenTelemetry, concrete APM, dashboard, concrete sampling, Kubernetes, or distributed infrastructure selected here.

## Alternatives Considered
- Minimal structured logs plus base metrics plus correlation -> Selected for MVP.
- Full distributed tracing/APM now -> Deferred: unnecessary without separate services.
- Logs only without correlation -> Rejected: insufficient diagnosis.
- Vendor-locked observability now -> Rejected: premature commitment.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-01 boundaries, ADR-04 FAILED/recovery, ADR-06 determinism, ADR-07 concurrency, ADR-08 durable persistence, and ADR-11 audit separation at creation time. Tooling and SLOs remain OPEN.
