# Cierre técnico de Fase 4.3

Fecha: 2026-10-22. Rama local: `fase-4.2-autorizacion-recuperacion`. No se
crearon commits, pushes, PRs, merges ni cambios de rama o de configuración
remota durante esta sesión.

Este documento es la referencia única del cierre. No cambia el estado de ningún
ADR y no declara una Fase 5 aprobada.

## 1. Alcance cerrado

Se completó el alcance D1-D7 ya autorizado y se resolvieron en código los
hallazgos bloqueantes de la auditoría R-1 a R-4 (R-1 queda pendiente de
verificación PostgreSQL):

- R-1: aislamiento de esquemas PostgreSQL de prueba y consultas de catálogo
  filtradas por `current_schema()`.
- R-2: evidencia persistida del destino directo mediante V13, sin backfill.
- R-3: corrección documental del orden real entre lookup de idempotencia,
  bloqueos y piernas.
- R-4: corrección documental de la semántica `REQUIRED` / `REQUIRES_NEW`.

R-5 a R-10 quedan aplazados. A-1 a A-8 conservan el estado registrado
(abiertos, fuera de alcance o no aprobados); el detalle está en
`docs/risk-register.md`.

## 2. Decisión R-2 aplicada

La decisión aprobada es la alternativa B del informe R-2:

- `transfer_operation_idempotency.destination_account_id UUID NULL`.
- Se persiste el destino suministrado en transferencias directas `CONFIRMED` y
  `REJECTED`.
- Las transferencias por beneficiario lo dejan `NULL`: su destino es evidencia
  de ejecución resuelta por el servidor y sigue reconstruible desde el
  movimiento `CREDIT` enlazado (ADR-18 Option A).
- El replay POST y la recuperación del ganador concurrente comparan el destino
  persistido cuando existe.
- Las filas directas anteriores a V13 no se backfillean ni se reconstruyen:
  quedan bajo la política aprobada `hash-only`.
- `financial_operations` no cambia. No se agrega FK ni índice.

La migración `V13__transfer_direct_destination_evidence.sql` agrega:

1. `ck_transfer_idem_destination_scope`, validada:
   `destination_account_id IS NULL OR path IS NOT DISTINCT FROM 'direct'`.
2. `ck_transfer_idem_direct_destination`, `NOT VALID`:
   `path IS DISTINCT FROM 'direct' OR destination_account_id IS NOT NULL`.

`NOT VALID` omite la validación inicial de las filas existentes, pero PostgreSQL
lo aplica a todo `INSERT` y `UPDATE` nuevo. No identifica, etiqueta ni exime
filas históricas. Una fila directa pre-V13 no puede actualizarse sin inventar
evidencia; eso es un residual aceptado, no una reparación.

## 3. Cambios técnicos

- `TransferOperationIdempotency` incorpora el campo, el parámetro de
  construcción, los getters y las invariantes/`@Check` equivalentes a V13.
- `TransferService` persiste el destino directo en confirmación y rechazo, y lo
  compara en `matchesIntent` solo cuando la fila lo tiene. GET sigue siendo
  hash-only porque no recibe cuerpo.
- V13 no modifica V1-V12. V12 sigue siendo la preflight de `CONFIRMED` con
  `operation_id`.
- Las pruebas H2 cubren persistencia directa/beneficiario, rechazo directo,
  invariantes Java, restricciones CHECK, replay con destino distinto y filas
  históricas.
- Las pruebas PostgreSQL cubren V12 preflight, V13 sobre legado, ausencia de
  backfill, rechazo de nuevas filas directas sin destino, aceptación de filas
  nuevas correctas y estado `convalidated` de ambas restricciones.
- `TransferPostgresMigrationTest` elimina sus esquemas aislados en `@AfterEach`;
  los tests de catálogo filtran por esquema.

## 4. Evidencia de validación

- Local: `.\mvnw.cmd -B -ntp verify` terminó con `EXIT=0` el 2026-10-22.
  Incluye Spotless, Checkstyle, JaCoCo y la suite H2. Surefire reportó 607
  pruebas ejecutadas, 0 fallos y 0 errores; 106 pruebas omitidas,
  correspondientes al gate PostgreSQL, por falta de Docker o `BANCO_PG_*`.
- PostgreSQL: no ejecutado en esta sesión porque no había Docker, `BANCO_PG_*`
  configurados ni una base de pruebas autorizada. No se afirma que el gate
  PostgreSQL haya pasado.
- CI: no ejecutado. El diff actual no está commiteado ni empujado.
- No se ejecutó el inventario real de filas históricas; requiere una base
  autorizada.

## 5. Residuales aceptados

- Filas directas pre-V13 sin destino: legibles y replayables por hash; no
  reparadas.
- Filas históricas que no cumplen la restricción direct-required: no se pueden
  actualizar sin aportar evidencia; no se backfillean.
- Una fila nueva con todas las columnas de intención `NULL` todavía satisface la
  rama histórica de V11. No se cierra con C, trigger ni marcador en V13.
- Las filas de V11-Phase 3 son todas `NULL` pero no históricas; V13 no las
  clasifica.
- Un escritor antiguo que inserte un directo sin destino fallará con la
  restricción activa. No hay evidencia de topología de despliegue en el
  repositorio; la convivencia de versiones debe resolverse antes de desplegar.
- A-1 (fallo de auditoría de conflicto), A-2 (identidad stub), A-3 (entropía de
  claves), A-4 (protección de ramas), A-7 (inventario real V12) y A-8
  (transacción ambiente) siguen abiertos o pendientes.

## 6. Cómo retomar

Desde la raíz del repositorio:

```powershell
.\mvnw.cmd -B -ntp verify
```

Con Docker disponible, ejecutar el gate PostgreSQL completo:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=*Postgres*" test
```

Sin Docker, configurar las tres variables `BANCO_PG_JDBC_URL`,
`BANCO_PG_USER` y `BANCO_PG_PASSWORD` contra una base de pruebas dedicada. El
gate nunca debe apuntar a datos reales.

Antes de commitear o desplegar:

1. Revisar el diff completo y confirmar que V12 y V13 entran en la misma
   release.
2. Ejecutar el gate PostgreSQL real contra el diff completo.
3. Ejecutar `docs/sql/transfer-confirmed-without-operation-inventory.sql` solo
   en una base autorizada y registrar el resultado.
4. Decidir si se acepta permanentemente `NOT VALID` y cómo se trata el hueco de
   filas nuevas con todo `NULL`.
5. Confirmar la estrategia de despliegue (ventana de mantenimiento o
   expand/contract) y que no convive un escritor antiguo.

No se debe interpretar este documento como autorización para commit, push,
merge, despliegue ni para modificar datos históricos.
