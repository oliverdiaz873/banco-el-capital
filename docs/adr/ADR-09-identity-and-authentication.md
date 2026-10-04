# ADR-09: Identity and Authentication

## Context
Financial operations require an authorized actor on the corresponding account, while keeping identity distinct from holders, customers, beneficiaries, and system roles, within ADR-01 boundaries and ADR-03 holder and lifecycle ownership.

## Decision
Establish decoupled identity/authentication capability. Identity provides authentication context; Accounts owns holder relationships and operability; Financial Operations applies the authorization rules needed to execute financial operations; the financial core MUST NOT couple to a concrete identity provider. A financial operation MAY execute only when its actor is authorized on the corresponding account. We do NOT assume authenticated user equals holder, holder equals customer, beneficiary equals user, or employee equals holder. Future MFA, roles, employees, administrators, external identity, OAuth/OIDC, delegation, and richer authorization remain possible but are NOT implemented here.

## Consequences
- What becomes easier: clear identity versus holder separation; evolvable auth without recoupling finance; least-privilege enforcement.
- What becomes harder: provider, session/token, credential, endpoint, and MFA choices deferred; cross-module authorization contracts still required.
- Trade-offs: decoupling now versus concrete login implementation later.

No JWT, OAuth2, OIDC, external provider, Spring Security, sessions versus tokens, user tables, credential schema, authentication endpoints, or concrete MFA selected here.

## Alternatives Considered
- Coupled finance-plus-login -> Rejected: recouples core to provider details.
- Decoupled identity capability -> Selected: stable boundaries and evolution.
- External provider now -> Deferred: valid future option, no current justification.
- Full RBAC now -> Deferred: premature authorization system.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-01, ADR-03, ADR-04, and ADR-05 at creation time. No premature infrastructure.
