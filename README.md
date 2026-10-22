# Banco El Capital

Modular Monolith bancario de portafolio con funcionalidad implementada de clientes, cuentas, depósitos, retiros, transferencias directas y transferencias por beneficiario.

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
Implementacion local de las capacidades Account Creation, Customer / Identity Foundation, Deposito, Retiro, Transferencia directa, Beneficiarios, Transiciones de cuenta, Historial de movimientos y Transferencia por beneficiario. Las migraciones Flyway vigentes son V1-V13. El cierre tecnico de Fase 4.3, sus residuales y los comandos para retomar el proyecto estan en `docs/phase-4.3-closure.md`.

El estado de aceptacion documental NO es uniforme: existen 22 ADRs en `docs/adr`, de los cuales 15 siguen en estado `Proposed`; los ADRs 12, 17, 18, 19, 20, 21 y 22 estan `Accepted`. La aceptacion de un ADR no implica por si sola que todas sus obligaciones de implementacion o validacion esten cerradas. No existe una aprobacion global del proyecto ni una Fase 5 aprobada.

## Feature: Transfer via saved beneficiary (Fases 4.1-4.3)
- Contrato XOR: `POST /api/v1/transfers` acepta exactamente uno de `destinationAccountId` o `beneficiaryId`; el destino de una transferencia por beneficiario siempre se resuelve en el servidor y nunca se toma del cuerpo.
- Doble autorizacion: `decideTransfer` sobre el titular de la cuenta origen y `decideManageBeneficiary` sobre el propietario persistido del beneficiario. La fase 1 es un camino rapido; la fase 2, dentro de `writeTx`, vuelve a leer origen y beneficiario y reevalua ambas decisiones antes de ejecutar las piernas. Tras adquirir ambos bloqueos de cuenta se revalida la idempotencia (comprobacion decisiva) y luego el beneficiario.
- Origen inexistente y origen ajeno devuelven el mismo `403` sin operacion, idempotencia, auditoria ni movimientos, y no consumen la clave. Despues de autorizar el origen, los rechazos de negocio conservan su `422` y su evidencia persistida.
- Evidencia: `transfer_beneficiary_usage` para transferencias CONFIRMED por beneficiario y columnas de intencion en `transfer_operation_idempotency` para CONFIRMED y REJECTED. El camino directo conserva su hash existente y, desde V13, persiste tambien el destino directo; el camino por beneficiario anade el discriminador `beneficiary-transfer`.
- Flyway V11 agrega la evidencia de intencion/uso. Flyway V12 agrega `ck_transfer_idem_confirmed_operation` (`outcome <> 'CONFIRMED' OR operation_id IS NOT NULL`) con un preflight que falla de forma explicita si encuentra filas invalidas, sin repararlas ni eliminarlas. Flyway V13 agrega `destination_account_id` a `transfer_operation_idempotency` con una restriccion de alcance validada y una restriccion direct-required `NOT VALID`; no backfillea filas historicas y no cambia `financial_operations`.
- El flujo administrado por `TransferService` fija `READ_COMMITTED` cuando abre su propia transaccion. Si se invoca dentro de una transaccion externa, `REQUIRED` permite que el llamador determine el aislamiento efectivo y el rollback final; esa limitacion esta documentada y probada. El rechazo tras segunda pierna, la auditoria de conflicto y la recarga del ganador usan `REQUIRES_NEW`; los rechazos ordinarios anteriores a cualquier pierna siguen la transaccion del llamador.
- Validacion: suite H2 completa con barreras deterministas de borrado/cambio de beneficiario, concurrencia de bloqueos y rollback del llamador. La suite PostgreSQL (incluida la prueba Flyway de esquema limpio, V12 preflight y V13 legado) requiere Docker o las variables `BANCO_PG_*`; si se omite, no cuenta como validacion PostgreSQL aprobada. El resultado exacto de la sesion de cierre esta en `docs/phase-4.3-closure.md`.

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

## Feature: Transfer beneficiary evidence
- La intencion se persiste para transferencias directas y por beneficiario, tanto CONFIRMED como REJECTED; las filas historicas anteriores a V11 conservan la rama de intencion toda NULL.
- `beneficiaries.api.BeneficiaryResolver.resolveForOwner` es el contrato publico read-only, owner-scoped y sin locks consumido por `financialops.transfer`.
- La tabla `transfer_beneficiary_usage` registra solo transferencias CONFIRMED por beneficiario, sin FK fisica; la evidencia sobrevive al borrado del beneficiario.
- Las lecturas autoritativas de titular y beneficiario usan proyecciones que no cargan entidades gestionadas en el contexto de persistencia; esto evita que una lectura de fase 2 seguida de un `findByIdForUpdate` reutilice un saldo obsoleto y pierda una actualizacion concurrente.
- El inventario de filas historicas que incumplirian V12 es de solo lectura; cualquier disposicion requiere revision manual y no se automatiza en la migracion. Ver `docs/risk-register.md`.
