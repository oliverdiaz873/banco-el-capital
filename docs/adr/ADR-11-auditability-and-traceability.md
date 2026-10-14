# ADR-11: Auditability and Traceability

## Context
Confirmed financial operations must be reconstructible for customers, operations, and future compliance, while keeping customer history, audit evidence, technical logs, and system events distinct, within ADR-01 transverse capability and ADR-02/05 compensation model.

## Decision
Establish Auditability and Traceability as a transverse capability, not as an independent financial module. History serves customers querying operations and movements. Audit Trail serves investigation and future operation/compliance and MUST be conceptually preservable and immutable for confirmed facts. Logs serve technical diagnosis and do NOT replace audit trail. Events serve system communication/reaction. Event Sourcing is NOT introduced by this ADR. A CONFIRMED operation MUST allow conceptual reconstruction of at least who, which operation, when, involved account/resource, result, movement relationship, correlation, beneficiary when used, and compensation relationship when present. Confirmed operations MUST NOT be retroactively modified; corrections preserve the original and use compensatory operations per ADR-02/05.

Transfer-beneficiary payloads (per ADR-21; transverse property unchanged): `TRANSFER_CONFIRMED` for a beneficiary transfer SHALL carry, in addition to the existing source/destination/amount/currency details, the `beneficiaryId` used and the persisted owner and alias snapshots, allowing historical reconstruction without consulting the current beneficiary. `TRANSFER_REJECTED` SHALL carry the attempted identifier with the same generic treatment for missing and foreign beneficiaries. No new audit event is introduced. `TRANSFER_CONFLICT` is recorded in an independent local transaction writing only the audit event; the 409 is returned ONLY if that transaction commits, otherwise 500 UNKNOWN with the same-key recovery message. No retroactive mutations; no PII, balances, holder data, or account status beyond the already authorized details. Retention policy scope is unchanged.

## Consequences
- What becomes easier: separable customer, audit, diagnostic, and reactive concerns; immutable confirmed evidence; future compliance path.
- What becomes harder: retention, physical storage, and tooling remain open; producer contracts still required.
- Trade-offs: transverse discipline now versus full compliance engine later.

No audit schema, tables, concrete events, Event Sourcing, Kafka, SIEM, logging tool, exact retention, physical storage, or complete compliance designed here.

## Alternatives Considered
- Transverse capability with separated history/audit/logs/events -> Selected.
- Audit as financial module -> Rejected: would own business rules incorrectly.
- Logs as audit -> Rejected: diagnostic retention and integrity insufficient.
- History as audit -> Rejected: mixes customer view with evidence.
- Event Sourcing now -> Rejected: unnecessary complexity for current need.

## Status
Proposed - awaiting final approval. NOT marked as Accepted before final review.

## Notes
- Compatible with ADR-01, ADR-02, ADR-04, and ADR-05 at creation time. Retention and physical implementation remain OPEN. No premature infrastructure.
