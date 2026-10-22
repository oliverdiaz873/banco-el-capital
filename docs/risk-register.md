# Registro de riesgos y decisiones pendientes

Este registro mantiene explícitos los riesgos que NO se cerraron en la Fase 4.3
(D1-D7) y el resultado de la auditoría R-1 a R-10. El cierre técnico de Fase 4.3
resuelve R-1, R-2, R-3 y R-4; R-5 a R-10 quedan aplazados y A-1 a A-8 conservan
el estado de la tabla inferior. No crea aprobaciones, no cambia el estado de
ningún ADR y no debe interpretarse como aceptación de una fase futura.

## Resultado del cierre técnico de Fase 4.3

| ID | Riesgo / hallazgo | Estado final | Evidencia | Residual aceptado |
|---|---|---|---|---|
| R-1 | Contaminación entre esquemas PostgreSQL de prueba | Resuelto (verificación PG pendiente) | `TransferPostgresMigrationTest` crea esquemas aislados y los elimina en `@AfterEach`; las consultas de catálogo de `TransferPostgresSchemaTest` y `WithdrawalPostgresSchemaTest` filtran por `current_schema()`. | La limpieza se prueba cuando el gate PostgreSQL se ejecuta con Docker o base externa; en esta sesión local no había una base PostgreSQL de pruebas autorizada y configurada. |
| R-2 | Evidencia persistida del destino directo | Resuelto con residual aceptado | V13 añade `destination_account_id` a `transfer_operation_idempotency`; el servicio lo persiste en `CONFIRMED` y `REJECTED` directos, lo deja `NULL` en beneficiario y lo compara en replay cuando existe. | Las filas directas pre-V13 no se backfillean: siguen en política hash-only. Un `direct` histórico sin destino no puede actualizarse sin inventar evidencia. El hueco preexistente de una fila nueva con todo `NULL` no se cierra con C, trigger ni marcador. |
| R-3 | Orden documentado de idempotencia y bloqueos | Resuelto documentalmente | `docs/transfer-design.md` y ADR-21 describen el lookup temprano no decisivo y el re-check decisivo después de ambos locks y de las dos piernas, antes de persistir operación/movimientos/idempotencia/auditoría. | El orden real no cambió; solo se corrigió la documentación para reflejarlo. |
| R-4 | Semántica `REQUIRED` / `REQUIRES_NEW` | Resuelto documentalmente | `docs/transfer-design.md` distingue rechazos ordinarios dentro de `writeTx` de las rutas `REQUIRES_NEW`: rechazo tras segunda pierna, auditoría de conflicto y recarga del ganador. | Un llamador con transacción ambiente sigue determinando aislamiento y rollback final; no se promete más de lo documentado. |
| R-5 a R-10 | Hallazgos no bloqueantes de la auditoría previa | Diferido | No bloquean el cierre técnico acordado; no se amplió el alcance ni se reabrieron sin autorización. | Deben reevaluarse al retomar el proyecto; el detalle permanece en el informe de auditoría previo. |

### Residuales de R-2 que no se convierten en normalidad

- Las filas pre-V13 con `path = 'direct'` y `destination_account_id IS NULL` son legado
  real: se leen y se replayan por hash, pero no se reparan.
- `CHECK ... NOT VALID` omite la validación inicial de filas existentes, pero
  PostgreSQL lo aplica a todo `INSERT` y `UPDATE` nuevo. No identifica ni etiqueta
  filas históricas.
- Una fila nueva con `path`, `source`, `beneficiary`, `destination`, `amount` y
  `currency` todos `NULL` todavía pasa la rama histórica de V11. V13 no cierra ese
  hueco; queda registrado y requiere una decisión separada si se quiere cerrar.
- Las filas de V11-Phase 3 son todas `NULL` pero no son históricas; no deben
  confundirse con el legado. V13 tampoco las clasifica.
- Un escritor antiguo que inserte un `direct` sin destino fallará con la restricción
  activa. El despliegue debe garantizar que no convive con escritores antiguos; el
  repositorio no contiene topología de despliegue para afirmarlo.

| ID | Riesgo / decisión pendiente | Estado | Por qué queda fuera | Evidencia o condición para cerrarlo |
|---|---|---|---|---|
| A-1 | Comportamiento ante fallo de la auditoría de conflictos | Abierto | Requiere una decisión independiente sobre si un conflicto debe devolver `409` cuando la auditoría no puede confirmarse. | ADR-11 y el flujo `TransferService.conflict` documentan la dependencia; hace falta una decisión explícita y pruebas de fallo de auditoría. |
| A-2 | Autenticación real y confianza en `X-Actor-Id` | Abierto | El proyecto sigue con el stub de identidad; `X-Actor-Id` es falsificable. | ADR-09 y el trabajo futuro de identidad. No se mitiga con cambios de transferencia. |
| A-3 | Entropía y custodia de claves de idempotencia | Abierto | ADR-06 mantiene la política de generación/custodia como OPEN; Fase 4.3 no la modifica. | Decisión normativa sobre CSPRNG (p. ej. >=122 bits), transporte TLS, tratamiento bearer y no reutilización. |
| A-4 | Protección de ramas y configuración remota | Abierto | No se alteró la configuración de GitHub ni la protección de `main`. | Verificación y aplicación fuera del repositorio; no se afirma que esté configurada. |
| A-5 | Funcionalidades bancarias nuevas | Fuera de alcance | La autorización D1-D7 no incluye FX, comisiones, límites, sobregiro, reversos, rails externos ni otros productos. | Requiere aprobación de producto y diseño separados. |
| A-6 | Fase 5 | No aprobada | No existe una decisión que cree o acepte una Fase 5. | Aprobación humana explícita con alcance y criterios propios. |
| A-7 | Filas históricas que incumplen V12 | Consulta preparada; inventario real pendiente | V12 falla de forma cerrada si encuentra `CONFIRMED` con `operation_id` nulo. No repara, borra ni reescribe evidencia histórica. | Ejecutar `docs/sql/transfer-confirmed-without-operation-inventory.sql` (solo agregados, sin claves ni datos financieros) en una base autorizada. En el esquema limpio H2 el conteo es 0, pero eso no sustituye el inventario de datos reales. Cualquier reparación requiere autorización y evidencia propia. |
| A-8 | Transacción ambiente del llamador | Limitación documentada | `REQUIRED` permite que el llamador determine el aislamiento efectivo y el rollback final; el servicio solo fija `READ_COMMITTED` cuando abre su propia transacción. | Pruebas de rollback del llamador y de aislamiento administrado; no se promete una garantía más fuerte de la documentada. |

## Límites de este registro

- No duplica riesgos ya descritos en ADRs aceptados; los referencia.
- No marca ningún riesgo como cerrado sin evidencia verificable en el repositorio.
- No autoriza implementación, migraciones, cambios de rama ni despliegues.
