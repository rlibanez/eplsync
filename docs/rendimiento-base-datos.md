# Medición de importaciones y consultas SQLite

## Prueba reproducible

Desde `backend/`:

```bash
./mvnw -Deplsync.test.performance=true -DargLine=-Xmx512m \
  -Dtest=DatabasePerformanceTests test
```

La prueba crea una base temporal y la elimina al finalizar. No utiliza la base de
la instalación, no descarga catálogos ni consulta un cliente torrent real.
Usa el esquema Flyway, WAL, `synchronous=FULL`, claves foráneas y un pool de dos
conexiones. Las tareas periódicas están desactivadas.

Datos sintéticos: 73.000 libros, 1.000 autores, sinopsis de unos 1.000 caracteres,
fechas de publicación distribuidas, 146.000 registros de descarga (dos revisiones
por libro) y un trabajo terminado con 73.000 elementos. El 10 % de los libros
cambia de revisión en la segunda importación. No representa todas las posibles
distribuciones de datos de un catálogo real.

Se miden importación nueva, actualización idéntica, actualización parcial,
previsualización, catálogo por título/fecha de incorporación, página profunda,
búsqueda textual, directorio de autores, resumen de Home, descargas, trabajos y
actualizaciones de revisión. Las consultas tienen un calentamiento y tres
repeticiones. Se imprime el tiempo de cada repetición, las preparaciones SQL de
Hibernate y una muestra del heap Java cada 10 ms.

`SQL` cuenta **preparaciones de sentencias**, no cada ejecución de un lote JDBC.
Una única preparación de INSERT/UPDATE puede ejecutar muchos registros. El heap
muestreado pertenece a toda la JVM: incluye contexto de pruebas, cachés y basura
pendiente de recolección; no equivale a memoria retenida ni al RSS del proceso.
Los tiempos son orientativos y no se utilizan como aserciones de regresión.

## Resultados del 10 de octubre de 2026

Comparación antes/después en la misma máquina, con la misma carga de catálogo.
Esta comparación se hizo con el heap predeterminado de la JVM; la ejecución
adicional con `-Xmx512m` completó todas las operaciones, incluida la sincronización
y los lectores concurrentes, sin agotar ese heap. En esa ejecución las muestras
de heap durante importación/previsualización alcanzaron como máximo 225,7 MiB;
no es una medición del máximo de todo el proceso.

| Operación | Antes | Después | Preparaciones SQL antes → después |
| --- | ---: | ---: | ---: |
| Importación nueva | 7,115 s | 5,937 s | 73.075 → 148 |
| Actualización sin cambios | 3,359 s | 2,043 s | 73.001 → 74 |
| Actualización del 10 % | 3,474 s | 2,205 s | 73.074 → 147 |
| Previsualización | 3,150 s | 2,028 s | 73.001 → 74 |
| Catálogo por incorporación, mediana | 0,029 s | 0,002 s | 2 → 2 |
| Actualizaciones de revisión, mediana | 29,164 s | 0,260 s | 2 → 2 |

La previsualización sin cambios preparaba una consulta por libro. Ahora carga
los existentes en bloques de hasta 1.000 identificadores. Se mantiene un único
commit para toda la importación: vaciar el contexto JPA o enviar un lote a SQLite
no confirma la transacción. Una cancelación o error revierte todos los lotes.
Las validaciones, fechas, resultados y detección de duplicados se conservan.

Las otras consultas medidas no justificaron cambios adicionales: catálogo por
título alrededor de 3 ms, página 1.000 de 50 elementos alrededor de 38 ms,
búsqueda textual alrededor de 25 ms, directorio de autores alrededor de 17 ms,
resumen Home alrededor de 27 ms y descargas alrededor de 40 ms. El trabajo de
73.000 elementos se resume en aproximadamente 49 ms y tres preparaciones SQL.
Estas cifras son de esta carga concreta; no garantizan tiempos equivalentes en
otros equipos, búsquedas o distribuciones de datos.

## Índices y planes

Se añaden tres índices en la migración inicial, conforme al criterio actual de
instalaciones nuevas. No se ejecuta `repair` ni una migración de instalaciones
experimentales anteriores.

- `idx_catalog_publication`: estado de publicación, fecha descendente e ID
  descendente; sirve a Novedades y Actualizaciones recientes.
- `idx_catalog_insert_minute`: expresión exacta de la ordenación por minuto de
  incorporación e ID ascendente. Preserva la ordenación existente.
- `idx_bulk_item_update_book`: ID del libro, estado y trabajo. Evita recorrer
  todos los elementos de trabajos en la subconsulta de Actualizaciones.

`EXPLAIN QUERY PLAN` confirma búsqueda por índice para publicación y elementos de
trabajos, y recorrido del índice de incorporación sin ordenación temporal. Antes
se recorrían las tablas y las ordenaciones del catálogo usaban un B-tree temporal.
Las pruebas de migración comprueban estos planes.

Los índices consumen espacio y añaden trabajo a las escrituras. La importación
nueva sigue mejorando aun con ese coste. No se cambia el tamaño del pool ni se
relajan límites de concurrencia o integridad para conseguir estos resultados.

## Concurrencia y límites

La prueba ejecuta dos lectores (20 consultas paginadas cada uno) simultáneamente
con una actualización de 7.300 libros y después con una sincronización que
actualiza 146.000 registros. Todas las consultas completan sin errores. En la
última ejecución con heap predeterminado, el conjunto de importación y lectores
tardó 2,237 s; la sincronización y lectores, 12,699 s.

La sincronización utiliza una respuesta sintética completa de 146.000 torrents:
verifica el procesamiento y la persistencia, pero no mide latencia de red ni el
coste del servidor qBittorrent. Las pruebas `SqliteConcurrencyTests` comprueban
además visibilidad de datos confirmados y recuperación/serialización de escrituras.

WAL permite leer mientras escribe la otra conexión. Un segundo lector puede
esperar a que quede libre la conexión de lectura. La importación continúa siendo
atómica y reserva el turno de escritura hasta terminar; otras escrituras pueden
esperar o agotar su plazo. Esta prueba no constituye una garantía de carga ilimitada.
Las búsquedas con comodín inicial y páginas muy profundas siguen teniendo costes
proporcionales al volumen recorrido. No se altera su semántica ni se introduce
FTS, cachés nuevas o importación por commits parciales.
