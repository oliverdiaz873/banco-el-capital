# ADR-03: Account / Holder / Balance Model

## Context
ADR-01 established a Modular Monolith where Accounts owns lifecycle/state and Account-Holder relationship, Identity/Customers owns identity, and Financial Operations owns financial invariants, with in-process contracts and local transactions.

ADR-02 established a Hybrid Financial Model where Financial Operation is intent plus lifecycle, Movement is confirmed immutable effect, and Balance is queryable state consistent with confirmed effects.

We must define the conceptual model of Account, Holder, Customer-Holder-Account relationship, holder roles, lifecycle/state, operability by state, currency, Product / Account Type, Balance, and the responsibility split between Accounts and Financial Operations, consistently with ADR-01 and ADR-02.

## Decision
Adopt Account owns the holder relationships. Account holds its holders/operators according to product rules. This does NOT mean Account owns customer identity.

Responsibilities stay separated:
- Identity / Customers: user/customer identity.
- Accounts: Account-Holder relationship and Account lifecycle.
- Financial Operations: financial invariants and monetary effects.

The model MUST allow 1..n holders per product rules. MVP MAY start with single-owner, but the conceptual structure MUST NOT prevent joint holder, authorized operator, or other product-justified roles. Authenticated User is NOT necessarily Holder. Holder is NOT necessarily Customer if future models require distinct operators.

Holder roles: MVP single-owner as minimum. Future extensions: joint holder, authorized operator, other product-specific roles. Employee/admin are system actors, NOT default holders.

Account lifecycle is explicit. MVP states: ACTIVE, BLOCKED, CLOSED. PENDING is excluded from MVP unless real async creation need appears; if MVP creation is synchronous, PENDING stays future (PENDING -> ACTIVE reserved).

ACTIVE MAY perform product-allowed financial operations. BLOCKED MUST NOT perform debit/non-allowed financial operations; query capability MAY remain per authorization; whether BLOCKED MAY receive credits is OPEN and MUST NOT be invented here. CLOSED is terminal in MVP and MUST NOT allow new financial operations. State transitions MUST be explicit and traceable, conceptually ACTIVE <-> BLOCKED -> ACTIVE -> CLOSED.

Currency: each Account has one currency, immutable after creation, single-currency MVP, multi-currency out of initial core. No FX, conversions, rates, or multi-currency accounts decided here.

Product / Account Type is separated from Account instance. Product defines varying rules such as allowed holders, currency, allowed operations, limits, overdraft, fees, interest, and other capabilities. MVP: one minimum product with enough abstraction to avoid hardcoding all rules in Account. No real multi-product catalog, configurable catalog, or complex rule system.

Balance, consistent with ADR-02: Balance is observable queryable financial state that MUST stay consistent with confirmed financial effects. We do NOT assert balance = SUM(all movements) as a universal requirement. Physical representation (stored, derived, materialized, physically hybrid) stays OPEN. Three separate balances are NOT introduced. Available Funds MAY exist as a validation concept for financial operations, with physical representation OPEN. Financial Operations MAY apply the available-funds rule without this ADR forcing an implementation.

### Operability matrix
| Capability | ACTIVE | BLOCKED | CLOSED |
|---|---|---|---|
| create | allowed per product/auth | not applicable | not applicable |
| consult | allowed per auth | allowed per auth | allowed per auth (history) |
| debit | allowed per product/funds | NOT allowed | NOT allowed |
| credit | allowed per product | OPEN - not decided here (see Notes) | NOT allowed |
| block | allowed transition ACTIVE->BLOCKED | already blocked | NOT allowed |
| close | allowed transition ->CLOSED | allowed transition ->CLOSED | terminal |

### Holder capability matrix
| Actor | consult | operate | authorize |
|---|---|---|---|
| owner / single titular | own accounts per auth | initiate own operations | subject to product/state |
| joint holder (future) | per rules | per rules | per rules |
| authorized operator (future) | per delegation | per delegation | per delegation |
| authenticated non-holder | none unless delegated role | none | none |

This is NOT a complete RBAC system. No MFA, OAuth/OIDC, token model, or API authorization details decided here.

## Consequences
- What becomes easier: clear Account boundary; conceptual multi-holder support; explicit lifecycle; stable currency; Account versus Product separation; coherence with ADR-01 and ADR-02; future evolution without coupling Customer directly to Account; testability.
- What becomes harder: slight Holder/Product abstraction; some authorization decisions deferred; physical balance pending; some blocked-account rules stay open.
- Trade-offs: minimal abstraction now versus hardcoding that blocks joint/product evolution later.

Precisions: Account does NOT calculate financial movements. Balance does NOT imply SUM(movements). ADR-02 Hybrid does NOT imply double-entry. Holder does NOT equal authenticated user. Product abstraction does NOT mean a product catalog. BLOCKED credit policy is not invented. PENDING is not added without need. This ADR does not resolve concurrency, persistence, or authorization implementation.

Ownership alternatives: A Customer owns Account was rejected as too rigid for joint/enterprise evolution. B Account owns Holder relationships was selected for better boundary and evolution. C Independent many-to-many without owner was rejected for diffuse invariants. Explicit lifecycle states are preferred over an active boolean because they express operability, transitions, and traceability. Physical balance alternatives remain OPEN.

Explicit non-decisions: PostgreSQL, ORM, SQL schema, indexes, locking, isolation level, Redis, Kafka, queues, CQRS, Event Sourcing, Double-entry accounting, microservices, distributed transactions, API endpoints, authentication protocol, MFA, complete authorization system, physical balance implementation, multi-currency, overdraft implementation, fees/interest implementation, complete banking product catalog.

## Alternatives Considered
- A Customer owns Account -> Rejected: couples Account to a simple Customer structure and blocks joint/product evolution.
- B Account owns Holder relationships -> Selected: better boundary, allows joint/operators/products without recoupling.
- C Independent relation without owner -> Rejected: unclear invariant ownership.
- Boolean active flag -> Rejected: cannot express blocked/closed operability and transitions.
- Fixing stored versus derived balance now -> Deferred: physical representation stays open per ADR-02.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- BLOCKED + CREDIT is OPEN: insufficient domain decision exists, so no definitive rule is invented; a later domain/authorization decision must resolve whether blocked accounts MAY accrue credits while debit remains prohibited.
- PENDING stays out of MVP under synchronous creation assumption; it MAY be introduced as PENDING -> ACTIVE if async creation is later justified.
- Consistency at creation: Accounts owns lifecycle plus holder relationships; Financial Operations keeps financial invariants per ADR-01/ADR-02; Balance stays physically open; no premature infrastructure; no unauthorized decisions added.
