# ADR-17: Feature-Based Packaging

## Context
After ADR-01 defined the Modular Monolith by responsibilities, ownership, and invariants, the
physical code organization still spread single capabilities across global technical packages. A
global `api/` package held the HTTP layer of every feature, and `financialops/` held Deposit and
Withdrawal mixed in one flat package. Locating a complete feature required jumping between
unrelated packages, ownership was hard to see, dependency control relied on human convention, and
the physical layout could contradict the conceptual boundaries.

The problem was not package names. A structure organized by global technical layers weakens
capability boundaries: any class can reach any other class of the same technical kind, and nothing
in the build prevents bypassing module contracts.

## Decision
Banco El Capital adopts **Feature-Based Packaging with explicit architectural boundaries**.
Modules are organized around business capabilities and responsibilities, not around global
technical layers.

The current structure is:

```text
com/bancoelcapital/
├── accounts/
│   ├── api/
│   ├── internal/
│   └── web/
├── customers/
│   ├── internal/
│   └── web/
├── financialops/
│   ├── core/
│   ├── deposit/
│   │   └── web/
│   └── withdrawal/
│       └── web/
├── identity/
├── audit/
│   ├── api/
│   └── internal/
├── app/
└── beneficiaries/
```

Packaging conventions:

- `api/` exists only when real inter-module contracts exist. It MUST NOT be created for visual
  symmetry.
- `internal/` exists when implementation must stay encapsulated behind the module API.
- `web/` is the HTTP boundary of its own feature or module. HTTP is a feature concern, not a
  global layer grouping every controller of the system.
- A module MAY stay flat when splitting it would not add real encapsulation. There is NO
  obligation to create `api/internal/web` in every module.

## Accounts
`accounts.api` is the public boundary of the module. It currently exposes:

- `AccountCreditService`
- `AccountCreditCommand`
- `AccountCreditResult`
- `AccountDebitService`
- `AccountDebitCommand`
- `AccountDebitResult`
- `AccountLookupService`

These are public contracts through concrete classes, consumed by Financial Operations
coordination. No future conversion to interfaces is intended by this ADR.

Everything else in Accounts stays encapsulated in `accounts.internal`, including the `Account`
entity, repositories, creation details, internal states and results, and persistence
implementation.

## Customers
Customers currently has `customers/internal/` plus `customers/web/` and deliberately NO
`customers.api/`.

The absence of `customers.api` is an architectural decision: no production consumer currently
needs a public Customers contract (cross-module identity needs are served through the
`IdentityGateway` contract owned by Identity and implemented by `JpaIdentityGateway`). A public
API MUST NOT be created only to keep visual symmetry with Accounts.

## Beneficiaries
`beneficiaries/api` is the public boundary of the module for transfer-time resolution only. It exposes exactly one read-only, owner-scoped resolution contract: given a beneficiary id and an owner, it returns the saved destination reference (destination account id, destination currency snapshot, alias snapshot). The exact signature is implementation detail within ADR-21 bounds. It MUST NOT expose repositories, JPA entities, holder data, balances, or account status.

Everything else in Beneficiaries stays encapsulated in `beneficiaries/internal`, including the `Beneficiary` entity, repositories, creation details, idempotency records, and persistence implementation. `beneficiaries/web` remains the HTTP boundary of the feature: other modules MUST NOT depend on it.

Allowed: `financialops.transfer -> beneficiaries.api` (resolution contract only; the single permitted exception to transfer/beneficiaries isolation, per ADR-01 and ADR-21). Prohibited: `financialops -> beneficiaries.internal`, `financialops -> beneficiaries.web`, `beneficiaries -> financialops.*`, and any `financialops` subfeature other than transfer depending on `beneficiaries.api`. No `beneficiaries.api` consumer beyond transfer is approved.

## Audit
`audit.api` is the public boundary and contains:

- `AuditRecorder`
- `AuditEntry`

`audit.internal` holds the store details:

- `JpaAuditRecorder`
- `AuditEvent`
- the corresponding repository and persistence.

Conceptual rule:

```text
business modules -> audit.api       (allowed)
business modules -> audit.internal  (prohibited)
```

## Identity
Identity remains a flat module. There is deliberately NO `identity/api` or `identity/internal`
because splitting it would not provide sufficient architectural benefit: it holds transverse
contracts (`AuthenticatedActor`, `AuthorizationService`, `IdentityGateway`) and their
implementations/stubs, without persistence to protect.

Ownership of `AuthenticatedActor`, `AuthorizationService`, `IdentityGateway`, and their
implementations/stubs stays in Identity. They MUST NOT be moved to a `shared` package only
because several features consume them.

## Financial Operations
Financial Operations is organized as:

```text
financialops/
├── core/
├── deposit/
│   └── web/
└── withdrawal/
    └── web/
```

- `core` holds the financial model shared by the operations (`FinancialOperation`,
  `Movement`, lifecycle types, repositories).
- `deposit` and `withdrawal` are subfeatures of Financial Operations, each with its own
  idempotency namespace, service, commands, results, and `web/` boundary.
- Deposit and Withdrawal MUST NOT depend on each other.
- `core` MUST NOT depend on Deposit or Withdrawal.
- There is currently NO external Financial Operations API because no external module consumes
  its contracts. `financialops/api` and `financialops/internal` are deliberately NOT created.

The owner relationship is preserved:

```text
Deposit -> accounts.api        (allowed)
Withdrawal -> accounts.api     (allowed)
Deposit -> accounts.internal   (prohibited)
Withdrawal -> accounts.internal (prohibited)
```

`DepositService` keeps using `AccountCreditService` and `WithdrawalService` keeps using
`AccountDebitService` (ADR-15, ADR-16). They MUST NOT be replaced by direct repository access.

## Web
The HTTP boundary lives next to its own feature:

```text
accounts.web
customers.web
financialops.deposit.web
financialops.withdrawal.web
```

This replaces the former global `api/` grouping, which was emptied of Java classes and is no
longer a code container. Web layers delegate behavior to their own module services and use
contracts; they MUST NOT reach repositories, JPA entities, or persistence internals to implement
behavior. `web` is not declared a mandatory package for every future module.

## Dependency Rules

### Rule A - modules consume public APIs
`module A -> module B.api` is allowed. `module A -> module B.internal` is prohibited.

### Rule B - no consumption of another module Web
`module A -> module B.web` is prohibited.

### Rule C - Web never touches repositories
`web -> repository` is prohibited (Accounts, Customers, Financial Operations, Audit, and
idempotency repositories alike).

### Rule D - Web never touches foreign entities
Direct Web dependence on `Account`, `Customer`, `FinancialOperation`, `Movement`, or
`AuditEvent` is prohibited.

### Rule E - Financial Operations uses the Accounts API only
`financialops -> accounts.api` is allowed. `financialops -> accounts.internal` and
`financialops -> accounts.web` are prohibited.

### Rule F - financial subfeatures stay isolated
`deposit -> withdrawal` and `withdrawal -> deposit` are prohibited. `core -> deposit` and
`core -> withdrawal` are prohibited.

### Rule G - Shared
There is currently NO `shared` package. Reuse alone does not imply transverse ownership and
MUST NOT justify creating one. If a genuine transverse concern without a clear domain owner
appears in the future, it MUST be justified separately.

## ArchUnit
These rules are not human conventions only. They are enforced at build time by
`ModuleBoundariesTest` (production code, `target/classes`):

- Rule A: `outsideAccountsMustNotDependOnAccountsInternal`,
  `outsideCustomersMustNotDependOnCustomersInternal`,
  `outsideAuditMustNotDependOnAuditInternal`, plus the pre-existing module-level prohibitions
  (`accountsMustNotDependOnCustomers`, `customersMustNotDependOnAccounts`,
  `financialOpsMustNotDependOnCustomers`, and their mirrors).
- Rule B: `outsideAccountsWebMustNotDependOnAccountsWeb`,
  `outsideCustomersWebMustNotDependOnCustomersWeb`,
  `outsideDepositWebMustNotDependOnDepositWeb`,
  `outsideWithdrawalWebMustNotDependOnWithdrawalWeb`.
- Rule C: `webPackagesMustNotDependOnRepositories`.
- Rule D: `webMustNotDependOnAccountEntity`, `webMustNotDependOnCustomerEntity`,
  `webMustNotDependOnFinancialEntities`, plus `financialOpsMustNotDependOnAccountEntity`,
  `financialOpsMustNotDependOnCustomerRepository`,
  `financialOpsMustNotDependOnAuditRepositories`.
- Rule E: `financialOpsMustNotDependOnAccountsInternal`,
  `financialOpsMustNotDependOnAccountsWeb`, plus the affirmative
  `depositServiceUsesAccountsContract` and `withdrawalServiceUsesAccountsContract`.
- Rule F: `depositClassesMustNotDependOnWithdrawalClasses`,
  `withdrawalClassesMustNotDependOnDepositClasses`,
  `coreFinancialModelMustNotDependOnDeposit/Withdrawal`,
  `financialOpsCoreMustNotDependOnDepositPackage/WithdrawalPackage`,
  `depositPackageMustNotDependOnWithdrawalPackage` and its mirror, and
  `depositWebMustNotDependOnWithdrawal` / `withdrawalWebMustNotDependOnDeposit`.
- Module APIs stay HTTP-free: `moduleApisMustNotDependOnWeb`.
- Cycle freedom: `moduleSlicesAreFreeOfCycles`.
- Beneficiaries (already enforced by `ModuleBoundariesTest`, recorded here to close the F6 documentation gap; test names are the definition, line ranges only auxiliary): `beneficiariesMustNotDependOnAccountsInternal`, `beneficiariesMustNotDependOnAccountsWeb`, `beneficiariesMustNotDependOnCustomers`, `beneficiariesMustNotDependOnFinancialOps`, `accountsMustNotDependOnBeneficiaries`, `outsideBeneficiariesMustNotDependOnBeneficiariesInternal`, `outsideBeneficiariesWebMustNotDependOnBeneficiariesWeb`, `webMustNotDependOnBeneficiaryEntity`, `financialOpsMustNotDependOnBeneficiariesInternal`, `financialOpsMustNotDependOnBeneficiariesWeb`, plus `financialOpsMustNotDependOnBeneficiaries` (currently passing vacuously via `allowEmptyShould`, since no such dependency exists yet).
- Future rule (proposed, NOT yet implemented — implementation phase only, MUST NOT be presented as enforced): an affirmative rule verifying that `TransferService` uses the `beneficiaries.api` resolution contract (mirroring `transferServiceUsesAccountsContracts`). It will complement — never replace preemptively — the blanket rule: the blanket `financialOpsMustNotDependOnBeneficiaries` plus the already-enforced `internal`/`web` negatives MUST remain in force until the affirmative rule is implemented and verified. Sequence: (1) design the affirmative contract-use rule; (2) implement and run the architecture tests; (3) complement — never weaken — the existing negatives; the blanket rule MUST NOT be removed or weakened until the affirmative rule effectively protects the intended boundary.

No new ArchUnit rule beyond the already-implemented negatives is introduced by this documentation phase; the beneficiary rules above are recorded from the already-implemented test, and the affirmative rule is a proposal only.

## Relation to ADR-14
ADR-14 is NOT modified. This ADR is compatible with the incremental evolution defined there.
Feature-based packaging is NOT a distribution decision: the architecture remains a Modular
Monolith with all modules in the same process. Organization by features eases future evolution
but does not extract processes.

## Alternatives Considered
- Global layered architecture (`controllers/`, `services/`, `repositories/`, `models/`) ->
  Rejected: it scatters one capability across global layers and weakens ownership.
- Creating `api/internal/web` in every module -> Rejected: ornamental structure with empty
  packages and no encapsulation gain.
- Creating `shared` immediately -> Rejected: no transverse concern without clear ownership
  currently justifies it.
- Interfaces for every public service -> Rejected for now: new abstractions without a concrete
  architectural need add indirection without solving a real problem.
- Microservices -> Out of scope: feature-based packaging does not imply process extraction.

## Consequences
- What becomes easier or more possible:
  - higher cohesion and features easier to locate;
  - explicit ownership and real encapsulation;
  - lower bypass risk through package-enforced contracts;
  - verifiable boundaries instead of human conventions;
  - incremental evolution without big rewrites.
- What becomes harder or more difficult:
  - more discipline in declared dependencies;
  - ArchUnit rules require maintenance alongside moves;
  - moving a class may require updating packages, imports, and tests;
  - deciding what is public API requires architectural analysis;
  - not every feature has an identical structure.
- Trade-offs:
  - analysis and discipline cost now versus bypass and coupling cost later;
  - asymmetric module shapes versus one-size-fits-all template symmetry.

## Migration History
- F0 - baseline: clean tree on `main`, H2 `verify` green (145 tests).
- F1 - ArchUnit dual: future-boundary rules expressed with current class names, no moves.
- F2 - `financialops/core` + `deposit` + `withdrawal` as real Java subpackages.
- F3 - Deposit/Withdrawal HTTP moved from global `api/` into feature `web/` packages.
- F4 - Accounts/Customers HTTP moved into feature `web/` packages; global `api/` emptied.
- F5 - `api/internal` encapsulation for Accounts, Customers, and Audit where real contracts
  exist; Identity and financialops features deliberately left without `api/internal` splits.
- F6 - documentation only (this ADR plus the ADR-01 addendum); no code changes.

## Status
Accepted - formally approved after the F0-F6 architecture review.

## Notes
- Language: MUST for decided architectural rules; SHOULD for recommendations; MAY for future
  possibilities.
- Consistency check at creation time: compatible with ADR-01 (modular boundaries),
  ADR-02/ADR-04 (financial model and lifecycle), ADR-06/ADR-07 (idempotency and concurrency),
  ADR-11 (audit as transverse capability), ADR-14 (incremental evolution), ADR-15/ADR-16
  (stored balance and withdrawal contracts). No code, transaction, endpoint, migration, or
  build change is introduced here.
