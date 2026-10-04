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
`com.bancoelcapital`: `identity`, `accounts`, `financialops`, `beneficiaries`, `audit`, `api`, `app`.
Limites por arquitectura y ArchUnit, no por build multi-modulo (ADR-14: extraer solo con evidencia).

## Perfiles
- `local`: PG via `DATABASE_URL/USER/PASSWORD` (sin secretos en repo).
- `test`: unit sin DB externa; integracion PG via Testcontainers cuando Docker este disponible.

## Comandos
- `./mvnw -B -ntp verify` (build + tests; Spotless check, Checkstyle, JaCoCo incluidos)
- Requiere JDK 21+ para compilar con `release 21` (host actual puede ser 23 compilando a 21).

## Estado
Setup inicial. ADRs 01-14 en `docs/adr` como Proposed. Sin logica bancaria implementada.

## Feature: Account Creation
- POST /api/v1/accounts con Idempotency-Key, X-Actor-Id y JSON holder/currency/productCode: 201 creado, 200 replay, 409 conflicto, 422 rechazo, 401/403 auth stub, 400 invalido.
- GET /api/v1/account-creations/{key} + header Idempotency-Key-Hash para recuperar resultado tras timeout.
- Stub MVP: el actor crea para si mismo (X-Actor-Id = holder) o con rol BANK_EMPLOYEE; StubIdentityGateway acepta id no vacio hasta modulo Customers.
- Cuenta nace ACTIVE, single-owner, producto BASIC, currency ISO explicita e inmutable; correcciones solo por compensacion futura.
