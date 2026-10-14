# Banco El Capital

Modular Monolith bancario (Setup inicial, sin funcionalidad bancaria todavia).

## Stack decidido
- Java 21 LTS + Spring Framework via Spring Boot 3.5.16
- Maven + Wrapper (`mvnw` / `mvnw.cmd`)
- PostgreSQL 16 como motor (Docker prerrequisito posterior, `postgres:16-alpine`)
- Flyway para migraciones
- JUnit 5 + AssertJ + Mockito (bordes) + Testcontainers PostgreSQL (hipotesis activa, requiere Docker)
- ArchUnit para limites ADR-01, Spotless + Checkstyle minimo, JaCoCo reporte
- GitHub Actions CI basica

## Estructura (modulo unico + modularizacion interna)
`com.bancoelcapital`: `identity`, `customers`, `accounts`, `financialops`, `beneficiaries`, `audit`, `api`, `app`.
Limites por arquitectura y ArchUnit, no por build multi-modulo (ADR-14: extraer solo con evidencia).

## Perfiles
- `local`: PG via `DATABASE_URL/USER/PASSWORD` (sin secretos en repo).
- `test`: unit sin DB externa; integracion PG via Testcontainers cuando Docker este disponible.

## Comandos
- `./mvnw -B -ntp verify` (build + tests; Spotless check, Checkstyle, JaCoCo incluidos)
- Requiere JDK 21+ para compilar con `release 21` (host actual puede ser 23 compilando a 21).

## Estado
Setup inicial. ADRs 01-14 en `docs/adr` como Proposed. Account Creation + Customer / Identity Foundation implementados. Fases 1-3 (evidencia e intencion de transferencias mediante beneficiarios) fusionadas via PR #8 en `main` remoto `191d7de22c8891995a5727e1c8d225d815350d98`; este arbol local sigue en `f802c24` sin sincronizar.

## Feature: Account Creation
- POST /api/v1/accounts con Idempotency-Key, X-Actor-Id y JSON holder/currency/productCode: 201 creado, 200 replay, 409 conflicto, 422 rechazo, 401/403 auth stub, 400 invalido.
- GET /api/v1/account-creations/{key} + header Idempotency-Key-Hash para recuperar resultado tras timeout.
- Auth MVP: el actor crea para si mismo (X-Actor-Id = holder) o con rol BANK_EMPLOYEE; la existencia del holder se valida contra Customers via `IdentityGateway` real (`JpaIdentityGateway`).
- Cuenta nace ACTIVE, single-owner, producto BASIC, currency ISO explicita e inmutable; correcciones solo por compensacion futura.

## Feature: Customer / Identity Foundation
- POST /api/v1/customers con Idempotency-Key, X-Actor-Id y JSON customerId/displayName?: 201 creado, 200 replay, 409 conflicto, 422 rechazo, 401/403 auth, 400 invalido.
- Customer ID client-provided `^[A-Za-z0-9._-]{1,64}$`, inmutable, PK; displayName opcional max 128, inmutable en MVP.
- Idempotencia SHA-256 `customerId|displayName` + misma semantica de replay/conflicto/concurrencia que Account Creation.
- Sin GET Customer publico en MVP: la existencia es contrato interno `Accounts -> IdentityGateway`; `accounts.holder_customer_id` permanece referencia logica, sin FK fisica.

## Feature: Transfer beneficiary evidence (Phases 1-3)
- Intencion persistida para transferencias directas existentes: `TransferService` escribe `source/null/direct/amount/currency` en CONFIRMED y REJECTED; filas historicas con intencion toda NULL siguen compatibles.
- Contrato publico read-only `beneficiaries.api` (`BeneficiaryResolver.resolveForOwner`): owner-scoped, sin locks, beneficiario ausente indistinguible de ajeno. Sin consumidores en `financialops` todavia (Fase 4 pendiente).
- Evidencia: tabla `transfer_beneficiary_usage` (solo CONFIRMED por beneficiario, sin FK) + columnas de intencion en `transfer_operation_idempotency`.
- Flyway V11 (`V11__transfer_beneficiary_evidence.sql`): 5 columnas NULLables + 3 CHECK (historico-o-completo, path permitido, coherencia path/beneficiario).
- Tests: `BeneficiaryResolverTest`, `TransferEvidencePersistenceTest`, `TransferIntentPersistenceTest`, `TransferEvidencePostgresTest` (6/6 en PG real), fixtures migrados en `TransferFoundation/ServiceTest`, reglas ArchUnit negativas intactas.
- PR #8 fusionado (merge `191d7de22c8891995a5727e1c8d225d815350d98`). Validacion CI corrida `38614962850`: build SUCCESS, postgres SUCCESS, 71 tests PG con 0 omitidos/fallos/errores en 22 clases, `POSTGRESQL GATE: all executed, none skipped, none failed`; segunda corrida `38614975120` tambien verde. Local: `verify "-Dtest=!*Postgres*"` con 324 tests verdes; los tests PG se omiten sin Docker y eso no prueba PG.
- Alcance: Fase 4 (ejecucion por beneficiario, XOR, hash con discriminador, READ COMMITTED pineado, barreras) no implementada. El merge no acepta enmiendas pendientes ni cierra el key-policy OPEN; trabajo futuro requiere autorizacion separada.
