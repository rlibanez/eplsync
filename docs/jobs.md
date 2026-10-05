# Envíos masivos y jobs

Los envíos múltiples usan POST con `dryRun` obligatorio en JSON y todos los parámetros
en el body. Los filtros van en `filters`; `sort` es una lista. `dryRun=true` prepara
una simulación sin crear trabajos ni enviar torrents; `includeDetails=true` permite
ver los elementos previstos por páginas: `detailPage` (desde 0) y `detailSize`
(por defecto 20, máximo 1000). El resumen cuenta toda la selección; `meta`
describe la página de detalles. Estos parámetros solo se admiten en simulaciones
con `includeDetails=true`. `dryRun=false` crea el job. No hay parámetros en la URL.


[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Envío bulk por búsqueda

`POST /api/torrent/books` usa los filtros de `GET /api/catalog/books` y crea un
trabajo persistente. Cada elemento envía un único torrent. La selección se guarda
antes de responder `202 Accepted`; no se espera al envío ni a las descargas. La
preparación de una selección grande puede tardar y ocupa una transacción de SQLite.

- Con `page` o `size`, selecciona únicamente esa página (índice desde cero;
  valores por defecto de paginación: página 0 y tamaño 20).
- Sin ambos, selecciona todos los libros que cumplen los filtros, recorriéndolos
  en lotes; no carga todo el catálogo en memoria.
- Sin filtros ni paginación requiere `all=true` de forma explícita.
- Los parámetros desconocidos se rechazan con `400` (por ejemplo, `pages`).
- Se ordena por `eplId` si no hay `sort`; con otra ordenación se añade `eplId`
  como desempate. La selección y los datos del libro quedan congelados en el trabajo.

Ejemplo: todos los libros en inglés con opciones y ejecución personalizadas:

```sh
curl -s -X POST 'http://localhost:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"options":{"start":false,"qbittorrent":{"category":"Libros","tags":["EPLsync","{language}"]}},"batchSize":100,"concurrency":2,"interval":"500ms","filters":{"language":"en"}}'
```

Ejemplo para enviar solo la segunda página de 50 resultados con los valores por defecto:

```sh
curl -s -X POST 'http://localhost:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"filters":{"author":"Brandon"},"page":1,"size":50}'
```

Configuración predeterminada:

```yaml
eplsync:
  torrent:
    bulk:
      batch-size: 100
      concurrency: 1
      interval: 500ms
      multiple-hashes: skip
```

Los campos omitidos o `null` del cuerpo heredan individualmente esos valores.
También se acepta `batch-size` como alias JSON de `batchSize`. `interval` admite
valores como `500ms`, `2s` o duraciones ISO-8601. `options` usa las mismas opciones
que el endpoint individual salvo `hash`, que no se admite en bulk.

- `batchSize`: 1–1000 elementos cargados por lote desde SQLite. No es el número
  de magnets en una petición a qBit: se sigue enviando uno por petición.
- `concurrency`: 1–16 operaciones de envío simultáneas.
- `interval`: 0ms–60s de separación mínima entre el inicio de operaciones,
  global para el trabajo, no por hilo. El coordinador espera hasta el próximo
  envío permitido y se despierta al finalizar una operación; no añade una pausa
  fija de 100ms entre pasadas. No acumula envíos atrasados para lanzarlos en ráfaga.
  La separación real puede ser mayor por la red, la persistencia o la carga.
  Los trabajos nuevos o reanudados se detectan mediante una comprobación cada 250ms
  como máximo mientras el coordinador está esperando.
- `size` de selección: entero positivo, sin máximo de aplicación e independiente de `batchSize`.

Se ejecuta un trabajo a la vez; los demás esperan. La concurrencia se aplica
al trabajo activo. Los valores efectivos de descarga, trackers, nombres y datos
para resolver tags se guardan por elemento. Cambiar el catálogo o los valores
predeterminados del YAML no altera los elementos pendientes. Las credenciales
no se guardan en las tablas de trabajos. Si cambia el cliente o su URL base,
el trabajo se pausa y exige restaurar el destino original para reanudar.

El `202` incluye `jobId`, `status`, `selectedBooks`, contadores y los valores
`batchSize`, `concurrency` e `interval` efectivos, además de una cabecera
`Location: /api/torrent/jobs/{jobId}`. Consulta y control:

```http
GET  /api/torrent/jobs/{jobId}
GET  /api/torrent/jobs/{jobId}/items?page=0&size=50
POST /api/torrent/jobs/{jobId}/pause
POST /api/torrent/jobs/{jobId}/resume
POST /api/torrent/jobs/{jobId}/cancel
```

La respuesta de progreso incluye `accepted`, `alreadyExists`, `skipped`, `failed`,
`pending`, `inFlight`, `cancelled`, `processedBooks` y la causa de pausa/error.
`processedBooks` cuenta libros con todos sus elementos procesados (sin pendientes,
en curso ni cancelados). `processedItems` suma aceptados, existentes, omitidos y
fallidos; los cancelados se cuentan aparte. El detalle paginado no expone snapshots internos ni credenciales.

Estados del trabajo: `QUEUED`, `RUNNING`, `RETRY_WAIT`, `PAUSED`, `COMPLETED`, `CANCELLED`.
Un trabajo completado puede contener errores: revisa sus contadores y elementos.

- Hashes repetidos dentro del trabajo y libros sin hashes válidos se omiten con
  explicación. Los libros con varios hashes siguen la política `multipleHashes`.
- Antes de cada alta se consulta si el hash existe; en ese caso se conserva el
  torrent sin modificarlo y se registra `ALREADY_EXISTS`.
- Un error específico del elemento se registra y el trabajo continúa.
- La autenticación rechazada o incompleta pausa el trabajo para reanudación manual.
- Fallos de red, respuesta remota inválida o timeout pausan los nuevos envíos y
  programan como máximo tres reintentos automáticos, tras 30, 60 y 120 segundos.
  Después se requiere reanudar manualmente. Cada reintento vuelve a consultar el
  hash antes de añadirlo; un timeout puede haber ocurrido después de que qBit lo aceptase.
- Pausa/cancelación impiden nuevos envíos. Las operaciones en curso pueden terminar;
  cancelar no borra ni detiene torrents ya enviados a qBit.
- Tras reiniciar EPLSync, los trabajos en ejecución se recuperan y los elementos
  en curso vuelven a comprobarse por hash. Los trabajos pausados siguen pausados
  y los cancelados no se reenvían.

Persistencia en `torrent_bulk_jobs` y `torrent_bulk_items`, independientes del
catálogo; un reset del catálogo no elimina la selección guardada. Se utiliza la
gestión de esquema Hibernate ya configurada (`ddl-auto: update`). La cola está
pensada para **una instancia de EPLSync por base de datos**; no incluye coordinación
entre varias réplicas. La comprobación de duplicados y el alta no son atómicas
frente a otros clientes que operen sobre qBit.

La concurrencia e intervalo regulan las peticiones de EPLSync. El número de
descargas activas y el consumo de recursos de los torrents añadidos dependen de
qBittorrent. `options.start: false` permite añadirlos detenidos.

#### Política de libros con varios hashes

En `eplsync.torrent.bulk.multiple-hashes` se configura el valor predeterminado
(`skip`). La petición puede sobrescribirlo mediante `multipleHashes`:

```sh
curl -s -X POST 'http://localhost:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"multipleHashes":"all","options":{"start":false},"filters":{"language":"en"}}'
```

- `all`: un elemento por cada hash válido del libro, en el orden de `links`.
- `skip`: omite los libros con más de un hash válido distinto.
- `first`: utiliza el primer hash válido de `links`, sin ordenar ni elegir por versión.

Los hashes se normalizan y deduplican. Un hash repetido entre libros se registra
como omitido y se envía una sola vez por trabajo. La política se guarda al crear
el trabajo: los existentes conservan su selección y los antiguos se interpretan
como `skip`. El endpoint individual conserva su selección explícita de hash.

`selectedBooks` cuenta libros; `selectedTorrents` cuenta hashes distintos
seleccionados, y `processedTorrents` los que ya no tienen elementos pendientes,
en curso o cancelados. `selectedItems` cuenta todas las filas del trabajo,
incluidos registros omitidos sin hash y duplicados. Los contadores `accepted`,
`alreadyExists`, `skipped`, `failed`, `pending`, `inFlight`, `cancelled` y
`processedItems` corresponden a esas filas. Un libro puede producir varios
resultados y no se considera procesado hasta resolverlos todos.

Cada hash recibe las mismas opciones del libro y el patrón de nombre configurado;
varios torrents del mismo libro pueden tener el mismo nombre mostrado.
Cancelar el trabajo detiene los envíos pendientes, sin eliminar torrents ya enviados.

#### Consultas y logs de los envíos

El adaptador reutiliza en memoria el método de autenticación seleccionado para
los envíos individuales y bulk. Las versiones se consultan al preparar ese estado,
no por cada torrent. El endpoint explícito de comprobación de conexión sigue
consultando las versiones al llamarlo.

En un trabajo bulk, tras las consultas iniciales, cada torrent nuevo necesita
`torrents/info` y `torrents/add`; uno existente solo necesita `torrents/info`.
Las categorías se consultan una sola vez por contexto, cuando se necesita validar
la categoría del primer torrent nuevo. No se consultan si no hay categoría o todos
los torrents ya existen.

Cada petición individual tiene su propio contexto. Los elementos concurrentes de
un trabajo bulk comparten el suyo, sin TTL y sin compartir categorías con otras
peticiones o trabajos. Al pausar y reanudar, reintentar un trabajo interrumpido o
recuperarlo tras reiniciar, se crea un contexto nuevo y se vuelven a consultar.
Si cambia una categoría en qBit durante una ejecución, la lista de ese contexto
no se refresca automáticamente. La autenticación sigue recuperándose por separado.

Un `401/403` en una lectura provoca recuperación de autenticación y un único
reintento de esa lectura. El POST de alta nunca se repite automáticamente dentro
del adaptador: un rechazo de autenticación invalida el estado para la siguiente
operación. Los reintentos del trabajador siguen consultando primero el hash.

Logs torrent:

- `INFO`: petición individual con `eplId`, selección bulk
  con filtros/paginación, creación del job y sus parámetros efectivos, ejecución,
  finalización y controles de pausa/reanudación/cancelación.
- `WARN`: fallos individuales y bulk con identificadores y motivo; interrupciones y reintentos.
- `DEBUG`: preparación de autenticación y actualización de categorías.
- `TRACE`: inicio y resultado por torrent, además del método, ruta API, estado HTTP
  y duración de las llamadas a qBit. Los cambios de estado del job incluyen un
  resumen de contadores en `INFO`.

No se registran claves, contraseñas, cookies, cabeceras de autenticación, magnets,
cuerpos HTTP ni snapshots de libros. Los filtros de búsqueda se registran en
`INFO`; se eliminan saltos de línea y se limita la longitud del texto.

Con la configuración actual, `root: INFO` es el valor heredado; el paquete
`com.rlibanez.eplsync: DEBUG` lo sobrescribe para nuestras clases, mostrando
DEBUG, INFO, WARN y ERROR. `TRACE` queda oculto. El logger específico
`org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver: ERROR`
solo muestra ERROR; no afecta al `GlobalExceptionHandler` de EPLSync.
Con `com.rlibanez.eplsync: DEBUG` los envíos correctos por elemento quedan ocultos;
los fallos siguen siendo visibles en `WARN`. Activa `TRACE` para investigar envíos concretos.

## Listar jobs actuales y pasados

```sh
curl -s 'http://localhost:8088/api/torrent/jobs' | jq
curl -s 'http://localhost:8088/api/torrent/jobs?page=1&size=20' | jq
curl -s 'http://localhost:8088/api/torrent/jobs?status=QUEUED,RUNNING,RETRY_WAIT,PAUSED' | jq
curl -s 'http://localhost:8088/api/torrent/jobs?status=COMPLETED,CANCELLED' | jq
```

`GET /api/torrent/jobs` incluye todos los estados por defecto y devuelve `items`
y `meta`. Cada elemento contiene la misma información y contadores que
`GET /api/torrent/jobs/{jobId}`. No requiere conocer los IDs previamente.

La paginación comienza en `page=0`, con `size=20` por defecto (sin máximo de aplicación).
El orden es fecha de creación descendente, con ID descendente como desempate.
`status` admite uno o varios estados separados por comas. Una página sin resultados
contiene `items: []`; parámetros desconocidos o inválidos devuelven `400`.
El historial corresponde a los jobs persistidos en la base de datos actual y
sobrevive a los reinicios. Los envíos individuales no crean jobs bulk.

## Filtrar elementos de un job

El endpoint existente `GET /api/torrent/jobs/{jobId}/items` admite `status`:

```sh
curl -s 'http://localhost:8088/api/torrent/jobs/ID/items?status=SKIPPED&page=0&size=500' | jq
curl -s 'http://localhost:8088/api/torrent/jobs/ID/items?status=SKIPPED,FAILED' | jq
```

El filtro se aplica en la base de datos antes de paginar: `meta.totalItems` y
`meta.totalPages` corresponden a los elementos filtrados. El motivo de cada omisión
o fallo sigue disponible en `items[].message`. Sin `status` se incluyen todos.
Los estados admitidos son `PENDING`, `IN_FLIGHT`, `ACCEPTED`, `ALREADY_EXISTS`,
`SKIPPED`, `FAILED` y `CANCELLED`, separados por comas si se indican varios.
Se mantiene el orden de selección, `page=0`, `size=50` por defecto y sin máximo de aplicación.
Los filtros desconocidos, vacíos o inválidos devuelven `400`.


La preparación recorre lotes de hasta 100 libros, tiene un presupuesto de dos
minutos y deduplica los hashes en un índice temporal en disco limitado a 128 MiB.
Un fallo cancela la creación completa del trabajo; no deja una selección parcial.
