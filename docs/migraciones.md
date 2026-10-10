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

## Conexiones, WAL y durabilidad

La base principal utiliza WAL, `synchronous=FULL`, dos conexiones Hikari y
`busy_timeout=10000` (10 segundos), configurados en `application.yaml`.
Estos parámetros se aplican a cada conexión física; el checkpoint automático
conserva el valor predeterminado de SQLite (1000 páginas).

Las transacciones de escritura gestionadas por Spring se coordinan dentro de la
instancia de EPL Sync antes de solicitar una conexión. Esto evita que dos
escritores lean el mismo estado y después fallen con `SQLITE_BUSY_SNAPSHOT`.
Las transacciones marcadas como lectura pueden trabajar en paralelo y ven datos
confirmados. La espera por el turno de escritura tiene un máximo de 30 segundos,
o el timeout de la transacción si es menor. No se reintentan automáticamente
operaciones destructivas. Una importación larga sigue retrasando otras escrituras,
pero permite consultas usando la segunda conexión.

Esta coordinación pertenece al proceso: no se deben ejecutar varias instancias
escritoras de EPL Sync contra el mismo archivo. WAL requiere almacenamiento local;
no se debe colocar `data` en NFS/SMB. Los archivos `eplsync.db-wal` y
`eplsync.db-shm` forman parte del funcionamiento de SQLite y no deben borrarse
manualmente mientras la aplicación está activa.

Para backups en caliente se debe utilizar la API de backup de SQLite o un
mecanismo equivalente consistente. Copiar únicamente el `.db` en funcionamiento
puede perder cambios confirmados que aún están en el WAL. El backup/restore desde
la interfaz sigue siendo una funcionalidad futura.

Las pruebas antiguas con `jdbc:sqlite::memory:` conservan una conexión porque cada
conexión crea una base independiente. `SqliteConcurrencyTests` utiliza una base
en disco y el pool de dos conexiones, incluyendo rollback y recuperación tras
la terminación abrupta de un proceso. Esa prueba no simula un corte eléctrico.

Para repetir las pruebas de importación, trabajos, sincronización y limpieza con
una base en disco y dos conexiones, desde `backend`:

```bash
EPLSYNC_TEST_IMPORT_LOAD=true ./mvnw -Deplsync.test.sqlite-disk=true -Dtest=BulkTests,UpdateTests,CatalogImportTests test
```

El modo solo afecta a esas pruebas y utiliza bases temporales independientes;
no abre la base de datos de la instalación.

## Claves foráneas y referencias históricas

La configuración JDBC activa `foreign_keys=ON` al crear cada conexión del pool,
antes de iniciar transacciones. Después de inicializar el esquema, EPL Sync
comprueba `PRAGMA foreign_key_check`: si encuentra referencias huérfanas o la
comprobación está desactivada, detiene el arranque sin borrar ni reparar datos.

La migración inicial declara relaciones desde `user_permission_overrides.user_id`
a `users.id`, y desde `torrent_bulk_items.job_id` y
`torrent_update_plans.job_id` a `torrent_bulk_jobs.id`, sin borrado en cascada.
Las relaciones de trabajos se comprueban al confirmar la transacción, permitiendo
el orden de inserción de Hibernate sin admitir referencias huérfanas. El servicio de cuentas elimina
primero los permisos individuales y después la cuenta; un borrado SQL directo
que dejaría esos permisos huérfanos se rechaza.

Las referencias a usuarios en eventos, trabajos, aprobaciones y limpiezas son
instantáneas históricas, no dependencias con borrado en cascada. Los registros de
descarga y los elementos de trabajos tampoco dependen de que el libro siga en el
catálogo. Una limpieza persistida conserva sus datos aunque se elimine su usuario
o su registro de historial; el reinicio completo y la retención de trabajos
mantienen sus reglas explícitas de eliminación.

El esquema inicial incorpora unicidad de `(job_id, position)`, posiciones y
reintentos no negativos, identificadores EPL enteros positivos y revisiones
numéricas, finitas y positivas. La revisión de un elemento de trabajo puede ser
nula cuando no hay metadatos, pero debe ser válida si se conoce. Los títulos y
autores no admiten valores vacíos ni solo espacios Unicode; sus límites y los de
los campos opcionales coinciden con el importador (autor: 16.384 caracteres).

Antes de publicar el esquema se ha consolidado esta revisión en
`V0_0_1__initial_schema.sql`, manteniendo la versión 0.0.1. Sus checksums cambian:
las bases experimentales previas deben recrearse y Flyway sigue rechazando
checksums distintos. No se añade una migración ni se intenta convertir esas bases.
Después de publicar, la migración inicial será inmutable.

La revisión de rendimiento añade también índices para publicación, ordenación
por minuto de incorporación y búsqueda de elementos de trabajos por libro dentro
del esquema inicial. Mediciones, costes y reproducción:
[rendimiento de SQLite](rendimiento-base-datos.md).
