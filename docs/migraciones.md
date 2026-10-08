# Migraciones de la base de datos

Flyway gestiona el esquema persistente de `eplsync.db`. Hibernate utiliza
`ddl-auto: validate`: comprueba los tipos y columnas mapeados por JPA, pero no
crea ni modifica tablas. Esa validación no comprueba por completo índices,
restricciones ni las tablas utilizadas exclusivamente mediante JDBC; las
migraciones y sus pruebas cubren esos aspectos.

## Primera versión

`backend/src/main/resources/db/migration/V0_0_1__initial_schema.sql` crea las
13 tablas actuales, sus índices y restricciones. Incluye catálogo, metadatos,
ajustes, usuarios, permisos, política de seguridad, eventos, trabajos,
descargas y planes de limpieza.

La fila inicial de política de seguridad se crea desde AccountStore después de
las migraciones para respetar la longitud mínima configurada mediante entorno.
Es inicialización de datos, no una modificación del esquema. Las bases
SQLite temporales de informes y exportaciones no pertenecen a este esquema.

Flyway ejecuta las migraciones antes de inicializar JPA y los servicios JDBC.
Guarda versiones, nombres, checksums y resultados en `flyway_schema_history`.
Para consultar la versión aplicada:

```sql
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

No se incorporan ni se marcan automáticamente bases experimentales anteriores
sin historial de Flyway. Una base no vacía sin ese historial provoca un error de
arranque. Durante esta fase de desarrollo, las instalaciones de prueba deben
recrearse desde cero. La aplicación no elimina esas bases automáticamente.

## Cambios posteriores

- Añadir un nuevo archivo SQL para cada cambio; nunca modificar, borrar o
  renombrar una migración publicada.
- Usar la versión de la aplicación que introduce el cambio: por ejemplo,
  `V0_0_2__add_column.sql`. Si hay varias, usar `V0_0_2_1`, `V0_0_2_2`, etc.
- Si una versión de la aplicación no cambia el esquema, no necesita migración.
- Antes de publicar 1.0.0 puede consolidarse la migración experimental inicial
  como `V1_0_0__initial_schema.sql`, recreando las bases de prueba.
- Conservar datos y documentar expresamente cambios destructivos. Probar tanto
  instalación vacía como actualización de una base de la versión anterior.
- En SQLite, cambios no soportados por ALTER TABLE requieren crear una tabla
  nueva, copiar los datos y restaurar sus índices y restricciones. Verificar las
  referencias y los datos durante ese proceso.
- Las migraciones SQL actuales se ejecutan transaccionalmente. No introducir
  instrucciones que requieran salir de la transacción sin revisar su recuperación.

Se valida el historial antes de migrar. Checksums alterados, migraciones
aplicadas que la aplicación desconoce y errores SQL impiden el arranque.
`baseline-on-migrate` está desactivado y `clean` está prohibido. No se ejecutan
bajadas automáticas de versión: volver a una versión antigua puede requerir
restaurar una copia compatible, realizada antes de actualizar. La interfaz de
backup/restore no forma parte de este cambio.

## Verificación

Las pruebas de integración usan Flyway y Hibernate validate, como la aplicación.
`DatabaseMigrationTests` verifica creación, repetición, conservación de usuarios,
actualización a una versión ficticia, checksums, rechazo de bases no versionadas,
rechazo de versiones futuras y rollback de una migración SQL fallida. Las
migraciones ficticias se generan en directorios temporales, nunca se distribuyen.

```sh
cd backend
./mvnw test
```

Referencia: [soporte de SQLite en Flyway](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite).
