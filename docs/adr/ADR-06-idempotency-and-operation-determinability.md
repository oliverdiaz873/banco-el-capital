# ADR-06: Idempotency and Operation Determinability

## Context
ADR-02 defines Operation as intent plus lifecycle and Movement as confirmed immutable effect. ADR-04 defines PENDING/CONFIRMED/REJECTED/FAILED with explicit recovery and states FAILED is not automatically zero effects. ADR-05 defines Transfer as one Operation plus two linked Movements. Retried, timed-out, disconnected, and concurrent requests must deterministically resolve to one logical operation without duplicating financial effects.

## Decision
Formalize idempotency as a property of the financial operation model. Each operation has a stable logical identity. The same intent with the same identity MUST NOT produce multiple financial effects. A PENDING retry continues to reference the same logical operation. A terminal operation MUST allow its result to be returned or reconstructed without creating another operation. Repeated requests after timeout or disconnect MUST be able to determine what happened. Concurrent requests with the same identity MUST converge deterministically to one logical operation and result. Incompatible payload with a previously used identity MUST produce a conflict rather than silently reusing the identity for another operation. Querying operation state and result is a first-class capability. CONFIRMED remains immutable.

We do NOT assert any concrete winning mechanism. The architectural decision expresses the property and invariant, not the physical mechanism that will implement it. Retention window, cleanup policy, storage, and indexes for identities and idempotency records remain OPEN.

Transfer-beneficiary hash (proposed design, NOT implemented in Phases 1-3; no code change in this PR): within the existing transfer idempotency namespace (no new namespace), the beneficiary-path intent identity WOULD BE `SHA-256` over the UTF-8 bytes of the canonical string `beneficiary-transfer|source-lower|beneficiaryId-lower|amount-base10|currency-upper`, hex-encoded lowercase (64 chars), where `|` is the literal ASCII separator U+007C with no surrounding whitespace, `source-lower` is the source account UUID canonical string lowercased, `beneficiaryId-lower` is the beneficiary UUID canonical string lowercased, `amount-base10` is the canonical decimal representation of the minor-units amount (base-10 digits only, no scientific notation, no grouping, no arbitrary trailing zeros), and `currency-upper` is the three-letter ISO code uppercased. The existing direct-transfer formula is unchanged and remains the only enforced identity. The path discriminator (`beneficiary-transfer` vs the direct-path shape) separates the intents of both paths so a direct request and a beneficiary request can never share an identity by construction of distinct inputs; this does not assert that SHA-256 collisions are mathematically impossible. Same key plus different path, beneficiary, destination, amount, or currency is a conflict under the rule selected above.

`Idempotency-Key` generation and custody (OPEN, no normative requirement in this PR): CSPRNG entropy (e.g. >=122 bits), TLS-only transport, bearer treatment and no-reuse remain open proposals for the future beneficiary-transfer API contract. This PR does not close the OPEN key-policy decision; retention, cleanup, storage and indexes remain OPEN.

## Consequences
- What becomes easier: safe retries; determinable results after timeout/disconnect; concurrent same-key convergence; traceability; coherence with ADR-02/04/05.
- What becomes harder: retention and conflict semantics must be preserved; payload-compatibility checks deferred; recovery design still required.
- Trade-offs: logical determinability now versus physical enforcement later; conflict strictness versus operational flexibility.

Alternatives: client-supplied versus server-assigned keys remain open; logical deduplication here versus physical constraint later; conflict-as-error was selected over silent reuse. No Redis, PostgreSQL, locks, indexes, SQL, HTTP, middleware, framework, or concrete key implementation decided here.

## Alternatives Considered
- Client-supplied key only -> Deferred: useful but generation/assignment policy remains open.
- Server-assigned key only -> Deferred: useful for discovery but client retry ergonomics open.
- Same key plus different payload as new operation -> Rejected: would silently conflate distinct intents.
- Same key plus different payload as conflict -> Selected: preserves integrity and traceability.
- Physical deduplication now -> Deferred: property first, mechanism in implementation.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-02, ADR-04, and ADR-05 at creation time. Retention duration, cleanup, storage, and indexes remain OPEN. No premature infrastructure.
