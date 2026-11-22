# Reporte y auditoría Codex — backend ERP

Fecha: 2026-11-21

## Alcance aplicado

Se auditó primero seguridad/sedes y se avanzó sin modificar Kardex ni los módulos excluidos (SUNAT, pasarela, reportes y frontend). Se preservaron los cambios previos del repositorio.

### Fase 1 — Seguridad, usuarios, roles y sedes

- Se retiró `@CrossOrigin(origins = "*")` de `UserController` y `RrhhAsistenciaController`. El CORS queda centralizado en `SecurityConfig` y se configura mediante `app.cors.allowed-origins`.
- Se verificó que `SecurityConfig` exige autenticación por defecto, centraliza CORS, permite `PATCH` y habilita seguridad de métodos.
- Se verificó que `GlobalExceptionHandler` mapea `AccessDeniedException` a HTTP 403 y `IllegalStateException` a HTTP 409.
- Persisten configuraciones inseguras de desarrollo en `application.properties` (`DB_PASSWORD`, `JWT_SECRET`, TOTP y `DDL_AUTO:update`) como *fallbacks*. El perfil `application-prod.properties` ya exige secretos del entorno y usa `ddl-auto=validate`. No se eliminaron los fallbacks de desarrollo porque requiere decidir cómo arrancará el entorno local/CI sin secretos.

### Fase 2 — Caja y turnos

- `TurnoCajaServiceImpl.abrir` ahora verifica en el servicio que un usuario no administrador abra caja únicamente en su sede asignada; una sede ajena produce 403.
- Se reemplazó la restricción limitada a la misma sede por `existsByUser_IdAndEstado`: un cajero no puede tener dos turnos abiertos en sedes distintas.
- ADMIN conserva alcance global para la apertura de caja, como pide la regla de negocio.
- Se agregó prueba de intento de apertura en sede ajena.

### Fase 3 — Kardex, stock e inventario

- No se reimplementó. La revisión confirmó el uso de `KardexMovimientoRequestDTO` basado en IDs y cobertura existente de Kardex/Stock.

### Fase 4 — Ventas

- `VentaServiceImpl.registrar` ahora valida la sede solicitada contra la sede del usuario autenticado antes de usar almacén o turno. Un MULTIFUNCIONAL no puede crear una venta para otra sede enviando un `sedeId` manipulado.
- Se agregó prueba negativa para esa escalada entre sedes.
- Se preservó el cálculo de precios/impuestos en backend, la validación de turno y la integración transaccional con Kardex existente.

### Fase 5 — Compras, recepción y proveedores

- Se añadió control de sede en la capa de servicio para crear, actualizar, consultar, listar, recibir, anular y eliminar compras.
- Para usuarios no ADMIN, el listado se restringe a su propia sede y una sede enviada distinta devuelve 403. ADMIN mantiene alcance global.
- Se agregó prueba negativa de alta de compra desde una sede ajena.

### Fases 6 a 9 — Devoluciones, finanzas, RR. HH. y archivos

- Se inspeccionaron los flujos de devoluciones/reembolsos, finanzas, asistencia y almacenamiento.
- Ya existen bloqueos transaccionales y controles de estado en devoluciones/reembolsos, y restricciones ADMIN para finanzas.
- Pendientes relevantes: verificar aislamiento por sede en todos los listados/detalles de devoluciones y reembolsos, validar contenido real (magic bytes/MIME) de archivos además de extensión, y completar una auditoría de acceso a legajos/documentos privados.

## Validación ejecutada

- `mvnw.cmd clean -DskipTests compile` con `JAVA_HOME=C:\Program Files\Java\jdk-17`: **BUILD SUCCESS**. Compiló 792 fuentes Java.
- `mvnw.cmd -DskipTests test-compile` con el mismo JDK: **BUILD SUCCESS**. Compiló 89 fuentes de prueba, incluidas las nuevas.
- Se intentó ejecutar `TurnoCajaServiceImplTest`, `VentaServiceImplTest` y `CompraServiceImplTest`. Surefire inició `CompraServiceImplTest` pero no produjo resultado durante más de 60 segundos; se interrumpió de forma segura. No hay resultado de pruebas que pueda declararse aprobado.
- `git diff --check`: sin errores de espacios.

## Pendientes priorizados

1. Resolver el bloqueo de Surefire y ejecutar pruebas focalizadas y la suite completa contra una base PostgreSQL exclusiva de pruebas.
2. Eliminar los fallbacks de secretos/credenciales y definir variables obligatorias para desarrollo y CI; rotar las credenciales actuales.
3. Añadir migraciones versionadas (Liquibase/Flyway) antes de imponer `ddl-auto=validate` fuera de producción.
4. Completar controles de sede en devoluciones, reembolsos, adjuntos, legajos y demás recursos que se resuelven por ID.
5. Agregar validación de magic bytes/MIME y políticas de descarga para archivos privados.
6. Corregir los avisos de Lombok `@Builder` en colecciones/defaults antes de depender de esos modelos en nuevos flujos.
