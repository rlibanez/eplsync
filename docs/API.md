# Referencia de la API de EPLsync

Dirección utilizada en todos los ejemplos: **`http://192.168.2.2:8088`**.

Esta referencia describe los endpoints implementados. Los valores configurados en
el despliegue pueden sobrescribir los predeterminados del repositorio.

## Índice

1. [Convenciones generales](#1-convenciones-generales)
2. [Importación del catálogo](#2-importación-del-catálogo)
3. [Consulta de libros y filtros compartidos](#3-consulta-de-libros-y-filtros-compartidos)
4. [Magnets y exportación](#4-magnets-y-exportación)
5. [Conexión con el cliente torrent](#5-conexión-con-el-cliente-torrent)
6. [Envío individual](#6-envío-individual)
7. [Envío múltiple o bulk](#7-envío-múltiple-o-bulk)
8. [Consulta y control de jobs](#8-consulta-y-control-de-jobs)
9. [Historial de descargas](#9-historial-de-descargas)
10. [Resumen de descargas](#10-resumen-de-descargas)
11. [Sincronización de descargas](#11-sincronización-de-descargas)
12. [Previsualización de actualizaciones](#12-previsualización-de-actualizaciones)
13. [Envío de actualizaciones](#13-envío-de-actualizaciones)
14. [Planes y limpieza de versiones anteriores](#14-planes-y-limpieza-de-versiones-anteriores)
15. [Renombrado de torrents](#15-renombrado-de-torrents)
16. [Salud de la aplicación](#16-salud-de-la-aplicación)
17. [Inventario de endpoints](#17-inventario-de-endpoints)
18. [Novedades y envío combinado con filtros](#18-novedades-y-envío-combinado-con-filtros)

## 1. Convenciones generales

- Los filtros y parámetros de selección van en la URL.
- Las opciones de envío van en un cuerpo JSON con `Content-Type: application/json`.
- Los campos de opciones omitidos o `null` heredan la configuración correspondiente.
- `page` empieza en `0`; `size` debe ser positivo. No existe el antiguo máximo de
  2.000 elementos. Los parámetros siguen sujetos al rango de sus tipos numéricos.
- Los estados distinguen mayúsculas y minúsculas salvo que se indique otra cosa.
- `publicationDate` usa `YYYY-MM-DD`. `insertDate`, `lastModifiedDate` y los
  instantes de descargas y jobs se expresan en UTC, con sufijo `Z`.
- `JOB_ID` y `HASH_DEL_LIBRO` son marcadores: deben sustituirse por valores reales.
- Los ejemplos que usan `jq` requieren esa herramienta para formatear el JSON.
- Un parámetro no documentado no añade funcionalidad: algunos endpoints lo
  rechazan y otros pueden ignorarlo.

### Respuesta paginada habitual

```json
{
  "items": [],
  "meta": {
    "page": 0,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0,
    "first": true,
    "last": true,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

La previsualización de importación utiliza un formato propio, descrito más abajo.

### Lectura local y operaciones remotas

| Operación | Consulta qBittorrent | Escribe en EPLsync | Puede modificar qBittorrent |
| --- | --- | --- | --- |
| Consultar catálogo o magnets | No | No | No |
| Consultar historial/resumen de descargas | No | No | No |
| Previsualizar novedades o revisiones nuevas | No | No | No |
| Consultar jobs y planes | No | No | No |
| Comprobar conexión | Sí, si está habilitado | No modifica el historial | No modifica torrents |
| Ejecutar sync | Sí | Sí | No |
| Enviar libros o actualizaciones | Al ejecutar el envío | Sí | Sí |
| Ejecutar cleanup | Sí, cuando corresponde | Sí | Sí, según la política guardada |
| Renombrar un torrent | Sí | No modifica el catálogo | Sí |

## 2. Importación del catálogo

| Método | Endpoint | Acción |
| --- | --- | --- |
| POST | `/api/catalog/import/reset` | Reemplaza el catálogo de libros con el CSV descargado. |
| POST | `/api/catalog/import/update` | Inserta y actualiza libros; conserva los que no aparecen en el CSV. |
| POST | `/api/catalog/import/preview` | Previsualiza cambios sin modificar el catálogo. |

### Parámetros de URL

| Parámetro | Endpoints | Descripción | Predeterminado |
| --- | --- | --- | --- |
| `url` | Los tres | URL HTTP/HTTPS del ZIP. | `eplsync.catalog.zip-url` |
| `includeDetails` | `preview` | Incluye detalles además del resumen. | `false` |
| `page` | `preview` | Página desde cero. | `0` |
| `size` | `preview` | Tamaño positivo. | `50` |

```bash
# Previsualizar cambios con detalle
curl -s -X POST \
  'http://192.168.2.2:8088/api/catalog/import/preview?includeDetails=true&page=0&size=50' | jq

# Actualizar el catálogo
curl -s -X POST \
  'http://192.168.2.2:8088/api/catalog/import/update' | jq

# Reemplazar el catálogo
curl -s -X POST \
  'http://192.168.2.2:8088/api/catalog/import/reset' | jq
```

El resumen contiene:

```text
success, message, recordsProcessed, errors,
recordsUpdated, recordsCreated, recordsUnchanged
```

Con `includeDetails=true`, la respuesta contiene `summary`, `page`, `size`,
`createdBooks` y `updatedBooks`. Cada actualización incluye `before`, `after` y
`changedFields`. La paginación se aplica por separado a creados y actualizados.

> `preview` no escribe en el catálogo, pero sí descarga y procesa el ZIP.
> `reset` reemplaza el catálogo; no es una previsualización.

### Reiniciar toda la base de datos

`POST /api/maintenance/reset` requiere un cuerpo JSON `{"confirm":true}`.
Elimina el catálogo, el historial local de descargas, los trabajos y sus elementos,
los planes de actualización y los registros de limpieza. Descarga el ZIP de la URL
configurada, extrae el CSV y reconstruye el catálogo desde cero en la misma operación.

Conserva el archivo SQLite y su esquema, la configuración, los logs y las
preferencias del navegador. No borra ni modifica torrents o archivos en qBittorrent.
El borrado y la importación son transaccionales: un fallo de descarga o importación
revierte todos los cambios. Un CSV vacío o con filas erróneas también cancela el reinicio.

Devuelve `200` con `success` y los contadores de filas eliminadas `catalogBooks`,
`downloads`, `jobs`, `jobItems`, `updatePlans` y `cleanupRecords`, además de `recordsImported` con el total de libros
importados. La confirmación
ausente o distinta de `true` produce `400`; las peticiones API, sincronizaciones
o envíos en curso pueden impedir el reinicio con `409`. Pausa o cancela los
trabajos y espera a que finalicen sus envíos antes de volver a intentarlo.
Los trabajos en pausa o en cola también se borran al confirmar el reinicio.

## 3. Consulta de libros y filtros compartidos

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/catalog/books/{eplId}` | Un objeto; `404` si el libro no existe. |
| GET | `/api/catalog/books` | Listado filtrado, con paginación opcional. |

```bash
curl -s 'http://192.168.2.2:8088/api/catalog/books/32' | jq

curl -s 'http://192.168.2.2:8088/api/catalog/books?eplId=32' | jq

curl -s \
  'http://192.168.2.2:8088/api/catalog/books?language=es&page=0&size=100&sort=eplId,asc' | jq
```

Los resultados incluyen `download.items`, con `id`, `revision`, `status` y
`completed` de los registros de descarga asociados. Es información guardada en
EPLsync; consultar un libro no ejecuta un sync.

### Filtros compartidos

Estos filtros funcionan en:

- `GET /api/catalog/books`
- `GET /api/catalog/magnets`
- `GET /api/catalog/magnets/export`
- `POST /api/torrent/books`
- `GET/POST /api/torrent/books?selection=new`
- `GET/POST /api/torrent/updates`
- `GET/POST /api/torrent/refresh`

| Parámetro | Significado / valores |
| --- | --- |
| `eplId` | Identificador exacto, entero positivo de tipo `Long`. |
| `author` | El autor contiene el texto indicado; hasta 255 caracteres. |
| `title` | El título contiene el texto indicado; hasta 512 caracteres. |
| `genres` | Los géneros contienen el texto indicado; hasta 512 caracteres. |
| `collection` | La colección contiene el texto indicado; hasta 255 caracteres. |
| `publicationYear` | Año exacto, entre `0` y `3000`. |
| `publicationYearFrom` | Año mínimo, incluido; entre `0` y `3000`. |
| `publicationYearTo` | Año máximo, incluido; entre `0` y `3000`. |
| `language` | `es`, `en`, `ca`, `gl`, `eu`, `fr`, `it`, `pt`, `de`, `eo`, `sv`, `other`. |
| `publicationStatus` | `PUBLISHED`, `UPDATED`, `UNKNOWN`. |
| `status` | `DISPONIBLE`, `VERIFICADO`, `DESCONOCIDO`. Puede repetirse para seleccionar varios. |
| `publicationDate` | Fecha exacta, `YYYY-MM-DD`. |
| `publicationDateFrom` | Fecha mínima, incluida. |
| `publicationDateTo` | Fecha máxima, incluida. |

Los filtros se combinan mediante **AND**. Los valores de `status` se combinan
mediante **OR**. `language` también admite nombres del enum como `ESPANOL`, sin
distinguir mayúsculas y minúsculas.

```bash
curl -s \
  'http://192.168.2.2:8088/api/catalog/books?language=es&publicationYearFrom=2000&publicationYearTo=2020&status=DISPONIBLE&status=VERIFICADO&size=100' | jq
```

Si se indica `publicationYear` o `publicationDate`, la coincidencia exacta tiene
prioridad sobre su rango correspondiente.

**No son filtros del catálogo:** `pages`, `revision`, `rating`, `volume`,
`insertDate` o `lastModifiedDate`. Que un campo aparezca en el JSON no significa
que esté implementado como filtro.

### Paginación y ordenación

| Parámetro | Comportamiento |
| --- | --- |
| `page` | Página desde `0`. |
| `size` | Número positivo de elementos. |
| `sort` | `campo,asc` o `campo,desc`; se puede repetir. |

- Sin `page` ni `size`, devuelve un array sin paginar.
- Si aparece cualquiera de ellos, devuelve `items` y `meta`; los valores
  omitidos son `page=0` y `size=20`.
- En `/api/catalog/books`, `sort` se aplica actualmente **solo a la búsqueda
  paginada**.
- Una búsqueda sin coincidencias devuelve un listado vacío, no `404`.

```bash
curl -s \
  'http://192.168.2.2:8088/api/catalog/books?size=100&sort=author,asc&sort=title,asc' | jq
```

Campos del modelo utilizables para ordenar:

```text
eplId, revision, author, title, genres, collection, volume,
publicationYear, synopsis, pages, language, publicationStatus,
publicationDate, insertDate, lastModifiedDate, status,
rating, votesCount, links
```

`download` es información añadida a la respuesta, no un campo persistido para ordenar.

## 4. Magnets y exportación

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/catalog/books/{eplId}/magnets` | Array de magnets de un libro. |
| GET | `/api/catalog/magnets` | Magnets según los filtros compartidos. |
| GET | `/api/catalog/magnets/export` | Texto con un magnet por línea; archivo `magnets.txt`. |

```bash
curl -s 'http://192.168.2.2:8088/api/catalog/books/32/magnets' | jq

curl -s \
  'http://192.168.2.2:8088/api/catalog/magnets?language=es&page=0&size=100&sort=eplId,asc' | jq

curl -s \
  'http://192.168.2.2:8088/api/catalog/magnets/export?language=es&sort=eplId,asc' \
  -o magnets.txt
```

- `/magnets` admite filtros, `page`, `size` y `sort`. Sin paginación devuelve un
  array; con ella, `items` y `meta`. Defaults parciales: página `0`, tamaño `20`.
- `/magnets/export` admite filtros y `sort`, **sin paginación**.
- Se deduplican hashes entre libros. El orden añade `eplId` como desempate.
- La paginación cuenta **magnets**, no libros.
- Los trackers proceden de la configuración; no existe un parámetro de petición
  para sobrescribirlos.
- No envía nada al cliente torrent. El endpoint individual devuelve `404` si el
  libro no existe, y puede devolver un array vacío si no tiene hashes válidos.

## 5. Conexión con el cliente torrent

```bash
curl -s 'http://192.168.2.2:8088/api/torrent/client/connection' | jq
```

`GET /api/torrent/client/connection` no necesita parámetros ni cuerpo.
Devuelve `enabled`, `connected`, `client`, `authMode`, `version` y `apiVersion`.

La URL y las credenciales del cliente proceden de la configuración de EPLsync,
no de esta petición.

## 6. Envío individual

```http
POST /api/torrent/books/{eplId}
```

Sin cuerpo utiliza la configuración:

```bash
curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/books/32' | jq
```

### Todas las opciones del cuerpo JSON

```json
{
  "hash": "HASH_DEL_LIBRO",
  "start": true,
  "savePath": "/downloads/epublibre",
  "rename": {
    "enabled": true,
    "pattern": "{author} - {title} [{eplId}] (r{revision})"
  },
  "qbittorrent": {
    "category": "Epublibre",
    "tags": ["EPLsync", "{language}"],
    "autoManagement": false
  }
}
```

| Campo | Descripción |
| --- | --- |
| `hash` | Hash perteneciente al libro. Necesario si tiene varios; admite hexadecimal de 40 caracteres o Base32 de 32. |
| `start` | `true`: iniciar; `false`: añadir detenido. |
| `savePath` | Ruta en el cliente remoto. Una ruta explícita no vacía requiere `autoManagement=false`. |
| `rename.enabled` | Activa/desactiva el nombre personalizado del torrent. |
| `rename.pattern` | Patrón del nombre mostrado; no renombra el archivo. |
| `qbittorrent.category` | Debe existir previamente. `""` permite añadir sin categoría. |
| `qbittorrent.tags` | Lista de etiquetas; `[]` permite añadir sin etiquetas. |
| `qbittorrent.autoManagement` | Activa/desactiva la gestión automática del destino. |

Los campos omitidos o `null` heredan los valores configurados.

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/books/32' \
  -H 'Content-Type: application/json' \
  -d '{"start":false,"rename":{"enabled":false},"qbittorrent":{"category":"Epublibre","tags":["EPLsync","{language}"],"autoManagement":true}}' | jq
```

### Placeholders en nombres y etiquetas

Se admiten los nombres de campos de `CatalogBook` enumerados en la sección de
ordenación: `{eplId}`, `{title}`, `{author}`, `{revision}`, `{language}`, etc.
No se admiten expresiones ni navegación por objetos.

- `{language}` genera el código ISO.
- Los campos nulos generan texto vacío.
- Los números no conservan ceros decimales innecesarios.
- Las etiquetas duplicadas se eliminan y las vacías se omiten.
- Las etiquetas resueltas no pueden contener comas ni caracteres de control.
- `category` no utiliza placeholders.

La respuesta incluye `eplId`, `hash`, `client` y `status`. Devuelve `202` para
`ACCEPTED` y `200` para `ALREADY_EXISTS`.

> Aceptar un envío no significa que el archivo esté descargado. Si el torrent ya
> existe, el envío no se utiliza para cambiar sus opciones.

## 7. Envío múltiple o bulk

```http
POST /api/torrent/books
```

Admite los [filtros compartidos](#3-consulta-de-libros-y-filtros-compartidos) y:

| Parámetro de URL | Significado |
| --- | --- |
| `page`, `size` | Seleccionan una página de **libros**. Defaults parciales: `0` y `20`. |
| `sort` | Orden de selección; se añade `eplId` como desempate cuando falta. |
| `all` | `true` permite seleccionar todo sin filtros ni paginación. Predeterminado: `false`. |

Sin `page`/`size`, selecciona todos los libros que cumplan los filtros.
Si tampoco hay filtros, se requiere `all=true`.

```bash
curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/books?language=es&page=0&size=1000&sort=eplId,asc' \
  -H 'Content-Type: application/json' \
  -d '{
    "batchSize": 100,
    "concurrency": 4,
    "interval": "100ms",
    "multipleHashes": "all",
    "options": {
      "start": true,
      "qbittorrent": {
        "category": "Epublibre",
        "tags": ["EPLsync", "{language}"],
        "autoManagement": true
      }
    }
  }' | jq
```

### Opciones del cuerpo

| Campo | Valores / significado |
| --- | --- |
| `batchSize` | `1–1000`; elementos cargados por lote desde SQLite. Alias: `batch-size`. |
| `concurrency` | `1–16`; máximo de operaciones simultáneas. |
| `interval` | `0ms–60s`; admite `100ms`, `2s` o duraciones ISO-8601. Separación global entre inicios. |
| `multipleHashes` | `all`, `skip` o `first`. |
| `options` | Opciones del envío individual, **excepto `hash`**. |

| Política | Comportamiento |
| --- | --- |
| `all` | Envía todos los hashes del libro. |
| `skip` | Omite libros con varios hashes. |
| `first` | Selecciona el primero de la lista. |

En el YAML del repositorio, los valores actuales son `batchSize=100`,
`concurrency=1`, `interval=500ms` y `multipleHashes=all`. La configuración del
entorno puede sobrescribirlos.

```bash
# Seleccionar todo el catálogo
curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/books?all=true' | jq
```

Devuelve `202`, un `jobId` y `Location: /api/torrent/jobs/{jobId}`.
Un libro puede producir varios elementos. `batchSize` no es el número de magnets
en una única petición a qBittorrent: se envían individualmente.

## 8. Consulta y control de jobs

| Método | Endpoint | Acción |
| --- | --- | --- |
| GET | `/api/torrent/jobs` | Jobs actuales y pasados. |
| GET | `/api/torrent/jobs/{jobId}` | Estado y contadores de un job. |
| GET | `/api/torrent/jobs/{jobId}/items` | Elementos y resultados individuales. |
| POST | `/api/torrent/jobs/{jobId}/pause` | Pausa nuevos envíos. |
| POST | `/api/torrent/jobs/{jobId}/resume` | Reanuda un job pausado o en espera de reintento. |
| POST | `/api/torrent/jobs/{jobId}/cancel` | Cancela elementos pendientes. |

### Listar jobs

| Parámetro | Predeterminado / valores |
| --- | --- |
| `page` | `0`. |
| `size` | `20`. |
| `status` | Uno o varios estados separados por comas; omitido: todos. |

```text
QUEUED, RUNNING, RETRY_WAIT, PAUSED, COMPLETED, CANCELLED
```

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/jobs?status=RUNNING,PAUSED,RETRY_WAIT&page=0&size=50' | jq
```

Se ordenan por creación descendente, con desempate por ID. No admite `sort`.

### Consultar elementos

| Parámetro | Predeterminado / valores |
| --- | --- |
| `page` | `0`. |
| `size` | `50`. |
| `status` | Uno o varios estados separados por comas; omitido: todos. |

```text
PENDING, IN_FLIGHT, ACCEPTED, ALREADY_EXISTS, SKIPPED, FAILED, CANCELLED
```

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/jobs/JOB_ID/items?status=FAILED,SKIPPED&size=1000' | jq
```

Cada elemento contiene `id`, `eplId`, `hash`, `status`, `attempts` y `message`.
Se filtra antes de paginar; el orden es la posición del elemento en el job.

El resumen del job incluye contadores de libros, torrents y elementos, además de
`accepted`, `alreadyExists`, `skipped`, `failed`, `pending`, `inFlight`,
`cancelled`, opciones efectivas, fechas, `retryAt` y `message`.

### Pausar, reanudar y cancelar

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/jobs/JOB_ID/pause' | jq
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/jobs/JOB_ID/resume' | jq
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/jobs/JOB_ID/cancel' | jq
```

No necesitan cuerpo. Un job completado o cancelado no se puede reanudar.

> Estos controles actúan sobre los envíos de EPLsync. No pausan ni borran torrents
> ya enviados a qBittorrent. Las operaciones en vuelo pueden finalizar.

## 9. Historial de descargas

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/downloads?eplId=32&page=0&size=100' | jq
```

`GET /api/torrent/downloads` devuelve registros completos, con revisión, hash,
cliente, origen, estado, fechas y `lastError`.

### Filtros

| Parámetro | Valores |
| --- | --- |
| `eplId` | Identificador positivo exacto. |
| `hash` | Hexadecimal de 40 o 64 caracteres, sin distinguir mayúsculas. |
| `revision` | Número no negativo exacto. |
| `status` | Uno o varios estados separados por comas. |
| `origin` | `EPLSYNC`, `DISCOVERED`; admite varios separados por comas. |
| `client` | Tipo de cliente, por ejemplo `qbittorrent`. |
| `clientInstanceId` | Identificador del destino concreto. |
| `completed` | `true` o `false`, según exista `completedAt`. |
| `page` | Predeterminado: `0`. |
| `size` | Predeterminado: `20`. |
| `sort` | Repetible; predeterminado: `createdAt,desc`, con desempate por `id`. |

### Estados de descarga

```text
SUBMITTED, ALREADY_EXISTS, UNKNOWN, QUEUED, DOWNLOADING,
PAUSED, CHECKING, DOWNLOADED, ERROR, NOT_FOUND
```

### Rangos de fechas

Cada campo admite los sufijos **`From`** y **`To`**:

```text
createdAt, requestedAt, submittedAt, completedAt,
discoveredAt, lastCheckedAt, lastSeenAt
```

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/downloads?status=DOWNLOADED&completedAtFrom=2026-09-28T00:00:00Z&completedAtTo=2026-09-28T23:59:59Z&size=100' | jq
```

Los rangos son inclusivos y rechazan límites invertidos. Las fechas nulas no
cumplen filtros de rango. Los instantes admiten formato ISO-8601; los ejemplos
usan UTC (`Z`).

### Campos de ordenación

```text
id, eplId, revision, hash, status, origin, client, clientInstanceId,
createdAt, requestedAt, submittedAt, completedAt,
discoveredAt, lastCheckedAt, lastSeenAt
```

Sin filtro de cliente o instancia, incluye todos los destinos guardados.
Los filtros distintos se combinan mediante AND; los valores de `status` y
`origin` dentro de cada lista, mediante OR.

> `completed=true` no equivale a `status=DOWNLOADED`: un registro `NOT_FOUND`
> puede conservar evidencia de una descarga completada anteriormente.

## 10. Resumen de descargas

```bash
curl -s 'http://192.168.2.2:8088/api/torrent/downloads/summary' | jq
```

Ejemplo de respuesta:

```json
{
  "total": 100,
  "byStatus": {
    "SUBMITTED": 0,
    "ALREADY_EXISTS": 0,
    "UNKNOWN": 0,
    "QUEUED": 0,
    "DOWNLOADING": 10,
    "PAUSED": 5,
    "CHECKING": 0,
    "DOWNLOADED": 85,
    "ERROR": 0,
    "NOT_FOUND": 0
  }
}
```

Acepta todos los filtros del historial **excepto `page`, `size` y `sort`**.
Todos los estados aparecen aunque su contador sea cero. `total` es su suma.

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/downloads/summary?origin=EPLSYNC&completed=true' | jq
```

El historial y el resumen leen la base de datos, sin consultar qBittorrent.
Cuentan **registros**, no libros ni torrents únicos.

## 11. Sincronización de descargas

```bash
curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/downloads/sync' | jq
```

No admite parámetros ni necesita cuerpo. Consulta el cliente y:

- Actualiza registros conocidos.
- Descubre torrents que coinciden por hash con el catálogo.
- Reconoce identidades v1 y v2 de torrents híbridos.
- Detecta ausencias y conserva el historial.
- No envía descargas ni ejecuta limpiezas.

### Contadores de respuesta

| Campo | Significado |
| --- | --- |
| `remoteTorrents` | Torrents observados en el cliente. |
| `checked` | Registros examinados, incluidos los nuevos. |
| `created` | Registros descubiertos y creados. |
| `updated` | Registros existentes cuyo estado o evidencia de finalización cambió. |
| `completed` | Finalizaciones registradas por primera vez en este sync. |
| `notFound` | Registros contabilizados como ausentes. |
| `ignored` | Torrents remotos sin correspondencia. |
| `checkedAt` | Instante de la instantánea en UTC. |

También devuelve `client` y `clientInstanceId`. Los contadores se solapan: no son
categorías independientes que deban sumarse.

Puede devolver `409` si hay envíos o una sincronización incompatible en curso.

## 12. Previsualización de actualizaciones

```http
GET /api/torrent/updates
```

| Parámetro | Descripción / predeterminado |
| --- | --- |
| `eplId` | Limita la búsqueda a un libro; omitido: todos los candidatos. |
| `includeNotFound` | `false`; con `true` incluye historial elegible desaparecido del cliente. |
| `multipleHashes` | `all`, `skip`, `first`; omitido: configuración bulk. |
| `page` | `0`. |
| `size` | `50`. |

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/updates?multipleHashes=all&page=0&size=1000' | jq

curl -s 'http://192.168.2.2:8088/api/torrent/updates?eplId=1234' | jq
```

Devuelve `items` y `meta`. Cada candidato incluye:

```text
eplId, title, catalogRevision, existingDownloads, targetHashes
```

Es un **dry-run de solo lectura**: compara el catálogo y el historial guardado;
no escribe ni consulta qBittorrent. Para actualizar antes el historial del
cliente, ejecutar el sync por separado.

Selecciona revisiones superiores de libros gestionados, con hashes válidos.
Excluye libros con envíos pendientes, revisiones nuevas ya enviadas/presentes y
situaciones inciertas que requieren reconciliación. `ERROR` o `UNKNOWN` por sí
solos no bastan como historial elegible.

Admite todos los filtros compartidos de `CatalogBookFilter`, incluido
`language=es`, combinados mediante AND. No admite `sort`.

## 13. Envío de actualizaciones

```http
POST /api/torrent/updates
```

### Parámetros de URL

| Parámetro | Descripción |
| --- | --- |
| Filtros del catálogo | Incluidos `eplId`, `language`, `author`, etc.; limitan los candidatos. |
| `includeNotFound` | Igual que en la previsualización; predeterminado: `false`. |

### Cuerpo JSON opcional

```json
{
  "previousVersions": "keep",
  "multipleHashes": "all",
  "batchSize": 100,
  "concurrency": 2,
  "interval": "100ms",
  "options": {
    "start": true,
    "qbittorrent": {
      "category": "Epublibre",
      "tags": ["EPLsync", "{language}"],
      "autoManagement": true
    }
  }
}
```

`batchSize` (alias `batch-size`), `concurrency`, `interval`, `multipleHashes` y
`options` tienen el significado y límites del bulk. `options.hash` no se admite.

| `previousVersions` | Política de limpieza posterior |
| --- | --- |
| `keep` | Predeterminada: conservar todo. |
| `removeTorrent` | Permitir eliminar solo el torrent anterior. |
| `removeTorrentAndFiles` | Permitir eliminar el torrent anterior y sus archivos. |

```bash
curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/updates?eplId=1234' \
  -H 'Content-Type: application/json' \
  -d '{"previousVersions":"removeTorrent","multipleHashes":"all"}' | jq
```

Devuelve `202`, un `jobId` y `Location: /api/torrent/jobs/{jobId}`. El progreso y
control utilizan los endpoints habituales de jobs.

- El POST no admite paginación; selecciona todos los candidatos que cumplan los
  filtros, no solo la página consultada en el GET.
- No hay borrado durante el envío.
- Los hashes, revisiones y opciones quedan congelados en el job.
- Sin candidatos se crea un job vacío ya completado.
- Repetir una petición no encola otra vez libros con una actualización pendiente.

## 14. Planes y limpieza de versiones anteriores

| Método | Endpoint | Acción |
| --- | --- | --- |
| GET | `/api/torrent/updates/{jobId}` | Objetivos, política y registros de limpieza. |
| POST | `/api/torrent/updates/{jobId}/cleanup` | Limpieza de un job. |
| POST | `/api/torrent/updates/cleanup` | Limpieza global de jobs pendientes. |

Los POST admiten únicamente:

| Parámetro | Descripción |
| --- | --- |
| `retryUnconfirmed` | `false` por defecto; `true` permite repetir borrados inciertos después de comprobar de nuevo las condiciones. |

```bash
curl -s 'http://192.168.2.2:8088/api/torrent/updates/JOB_ID' | jq

curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/updates/JOB_ID/cleanup' | jq

curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/updates/cleanup' | jq

curl -s -X POST \
  'http://192.168.2.2:8088/api/torrent/updates/cleanup?retryUnconfirmed=true' | jq
```

### Condiciones de borrado

Antes de eliminar un torrent anterior deben estar completos todos los torrents
seleccionados de la revisión nueva. Se aplica la política guardada, sin cambiarla
por parámetros de limpieza.

Se bloquean torrents compartidos con otros libros e identidades coincidentes con
la nueva revisión. Para borrar datos también se bloquean rutas compartidas o no
verificables. La comprobación usa rutas remotas reportadas por el cliente; no
inspecciona enlaces simbólicos/hard links ni puede impedir cambios externos
simultáneos.

### Estados de limpieza

| Estado | Significado |
| --- | --- |
| `KEPT` | Se conserva por política. |
| `WAITING` | La nueva revisión todavía no está disponible o completa. |
| `BLOCKED` | Una protección impide el borrado. |
| `REQUESTED` | Borrado solicitado, sin confirmar ausencia. |
| `REMOVED` | Ausencia confirmada en el cliente. |

El GET del plan y el POST individual devuelven `jobId`, `previousVersions`,
`updates` e `items`. Los items incluyen identificadores, hash, estado, mensaje y
fecha de actualización.

El historial de descargas se conserva y pasa a `NOT_FOUND` al confirmar la
ausencia. `REMOVED` confirma que el torrent desapareció; no verifica físicamente
el borrado de archivos en el almacenamiento remoto.

### Limpieza global

Selecciona planes con política de borrado y registros `WAITING`, `BLOCKED` o
`REQUESTED`. Excluye `KEPT` y `REMOVED`.

Comparte una consulta inicial a qBittorrent y otra de confirmación si intenta
borrados. Evita borrar dos veces el mismo torrent y protege objetivos de otros
planes seleccionados. Las políticas incompatibles sobre un mismo torrent se
bloquean. En cadenas de revisiones puede ser necesario repetir la limpieza.

Respuesta global:

```text
selectedJobs, failedJobs, checked, removed,
waiting, blocked, requested, jobs
```

Cada entrada de `jobs` incluye `jobId`, `checked`, `removed`, `waiting`, `blocked`,
`requested` y `error` (nulo si no hay error).

Los contadores describen registros pendientes seleccionados en esa petición, no
torrents únicos ni todo el historial. Un destino incompatible se informa por job
sin modificarlo. Los fallos iniciales de conexión impiden comenzar el borrado;
los fallos de confirmación mantienen resultados inciertos.

> No hay limpieza automática. Completar un job o ejecutar un sync no la activa.
> Los envíos y el sync se coordinan con la limpieza; una operación incompatible
> puede devolver `409`.

## 15. Renombrado de torrents

```http
POST /api/catalog/books/{eplId}/torrents/{hash}/rename
```

```bash
curl -s -X POST \
  'http://192.168.2.2:8088/api/catalog/books/32/torrents/HASH_DEL_LIBRO/rename' | jq
```

- Sin cuerpo ni parámetros adicionales.
- Usa el patrón de renombrado configurado; no admite uno personalizado en esta petición.
- El hash debe pertenecer al libro del catálogo y ser hexadecimal de 40 caracteres
  o Base32 de 32.
- Requiere cliente y renombrado habilitados.
- Cambia el nombre mostrado del torrent, no los nombres de sus archivos.
- Devuelve `eplId`, `hash`, `name` y `client`.

## 16. Salud de la aplicación

```bash
curl -s 'http://192.168.2.2:8088/actuator/health' | jq
```

El proyecto incluye Actuator. Este endpoint comprueba la salud de EPLsync, no
sustituye al test de conexión con qBittorrent. Su exposición puede modificarse
mediante la configuración de despliegue.

## 17. Inventario de endpoints

| Método | Ruta |
| --- | --- |
| POST | `/api/catalog/import/reset` |
| POST | `/api/maintenance/reset` |
| POST | `/api/catalog/import/update` |
| POST | `/api/catalog/import/preview` |
| GET | `/api/catalog/books` |
| GET | `/api/catalog/books/{eplId}` |
| GET | `/api/catalog/books/{eplId}/magnets` |
| GET | `/api/catalog/magnets` |
| GET | `/api/catalog/magnets/export` |
| POST | `/api/catalog/books/{eplId}/torrents/{hash}/rename` |
| GET | `/api/torrent/client/connection` |
| POST | `/api/torrent/books/{eplId}` |
| POST | `/api/torrent/books` (bulk normal o `selection=new`) |
| GET | `/api/torrent/books?selection=new` |
| GET | `/api/torrent/refresh` |
| POST | `/api/torrent/refresh` |
| GET | `/api/torrent/jobs` |
| GET | `/api/torrent/jobs/{jobId}` |
| GET | `/api/torrent/jobs/{jobId}/items` |
| POST | `/api/torrent/jobs/{jobId}/pause` |
| POST | `/api/torrent/jobs/{jobId}/resume` |
| POST | `/api/torrent/jobs/{jobId}/cancel` |
| GET | `/api/torrent/downloads` |
| GET | `/api/torrent/downloads/summary` |
| POST | `/api/torrent/downloads/sync` |
| GET | `/api/torrent/updates` |
| POST | `/api/torrent/updates` |
| GET | `/api/torrent/updates/{jobId}` |
| POST | `/api/torrent/updates/{jobId}/cleanup` |
| POST | `/api/torrent/updates/cleanup` |
| GET | `/api/ui/config` |
| GET | `/actuator/health` |

No hay actualmente un endpoint de envío mediante una lista explícita de `eplId`,
ni endpoints generales para pausar o borrar torrents arbitrarios del cliente.
Los controles de jobs actúan sobre los envíos de EPLsync.

### Formato de las fechas de seguimiento del catálogo

`insertDate` y `lastModifiedDate` conservan sus nombres y contienen fecha y hora
UTC (por ejemplo, `2026-09-29T07:16:18.123Z`). La inserción se conserva al actualizar;
la modificación es `null` hasta el primer cambio y no cambia al reimportar datos
idénticos. `publicationDate` mantiene el formato `YYYY-MM-DD`.

### Detección de torrents existentes al enviar

Los envíos reconocen los identificadores `hash`, `infohash_v1` e `infohash_v2`
de qBittorrent, incluidos los torrents híbridos. Cada solicitud individual o
ejecución bulk obtiene un índice inicial compartido entre sus envíos y añade
los hashes aceptados. Tras un fallo de envío se invalida ese índice; la próxima
comprobación vuelve a consultarlo. Al reanudar un trabajo se crea un contexto nuevo.

El índice no es una monitorización continua: los cambios realizados por otros
clientes durante un trabajo pueden no verse hasta que se vuelva a consultar.

## 18. Novedades y envío combinado con filtros

| Método | Endpoint | Selección |
| --- | --- | --- |
| GET | `/api/torrent/books?selection=new` | Previsualiza libros sin historial en el cliente actual. |
| POST | `/api/torrent/books?selection=new` | Crea un job solo con esos libros nuevos. |
| GET | `/api/torrent/updates` | Previsualiza revisiones superiores de libros gestionados. |
| POST | `/api/torrent/updates` | Crea un job solo con esas revisiones superiores. |
| GET | `/api/torrent/refresh` | Previsualiza la unión de novedades y revisiones superiores. |
| POST | `/api/torrent/refresh` | Crea **un único job** con ambos grupos. |

Todos admiten los [filtros del catálogo](#3-consulta-de-libros-y-filtros-compartidos),
como `language=es`, `author`, `eplId`, `publicationYearFrom` o `status`.
Los filtros se aplican al **libro actual del catálogo**, tanto para novedades
como para actualizaciones. Los candidatos se ordenan por `eplId` ascendente.
No se admite `sort` en estas selecciones.

### Qué significa nuevo

Un libro nuevo es un `eplId` **sin ningún registro en el historial de la instancia
actual del cliente** (tipo y URL configurados). No depende de `insertDate`, de la
fecha de importación ni de que su revisión sea `1.0`. Un registro de otra instancia
no lo excluye. Tener únicamente `ERROR`, `UNKNOWN` o `NOT_FOUND` tampoco convierte
un libro en nuevo: esos casos requieren revisar o recuperar su historial.

Se excluyen libros reservados por envíos pendientes/en curso, incluso si el job
está pausado, y candidatos sin hashes válidos o descartados por `multipleHashes`.
Las actualizaciones mantienen las reglas de revisión superior e historial elegible.

La previsualización **no contacta con qBittorrent y no modifica datos**. Si se han
añadido o eliminado torrents manualmente, se puede ejecutar antes el sync para
actualizar el historial. Estos endpoints no importan el CSV ni ejecutan sync
implícitamente. Los torrents ya existentes se comprueban durante el envío.

### Parámetros y respuestas

| Parámetro | GET | POST |
| --- | --- | --- |
| Filtros de `CatalogBookFilter` | URL | URL |
| `selection=new` | Obligatorio en `/books` | Obligatorio en `/books` |
| `includeNotFound` | URL, solo `/updates` y `/refresh`; predeterminado `false` | Igual |
| `multipleHashes` | URL: `all`, `first`, `skip` | Cuerpo JSON |
| `page`, `size` | URL: `0`, `50` por defecto; `page >= 0`, `size > 0` | No admitidos |

GET devuelve `items` y `meta`. Cada item contiene `eplId`, `title`,
`catalogRevision`, `existingDownloads` y `targetHashes`. En las novedades,
`existingDownloads` está vacío; en las actualizaciones contiene las versiones
anteriores. La paginación se aplica **después de seleccionar los candidatos**.

POST selecciona **todos los candidatos que cumplan los filtros**, no solo la página
previsualizada. No requiere `all=true`: sin filtros, selecciona los candidatos de
todos los idiomas. Los parámetros desconocidos, vacíos o repetidos se rechazan con
`400` (salvo `status`, que permite varios valores).

El cuerpo opcional de `POST /refresh` es el mismo que el de `POST /updates`:
`previousVersions`, `multipleHashes`, `batchSize` (alias `batch-size`), `concurrency`,
`interval` y `options`. Los límites y opciones de descarga son los del bulk;
`options.hash` no se admite. `previousVersions` es `keep` por defecto, y solo afecta
a las versiones anteriores de los libros actualizados.

`POST /books?selection=new` usa el cuerpo bulk: las mismas opciones excepto
`previousVersions`, porque las novedades no tienen versiones anteriores que limpiar.
Los valores de descarga y ejecución omitidos heredan `application.yaml`.

POST devuelve `202`, el resumen del job y `Location: /api/torrent/jobs/{jobId}`.
Los endpoints habituales de progreso, items, pausa, reanudación y cancelación sirven
para estos jobs. Repetir el POST no vuelve a reservar libros pendientes. Sin
candidatos se devuelve un job vacío `COMPLETED`. La selección y las opciones se
congelan al crear el job; el GET previo no reserva libros.

### Flujo recomendado: mantener una biblioteca en español

Después de importar el CSV, previsualizar novedades y revisiones nuevas juntas:

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/refresh?language=es&multipleHashes=all&size=1000' | jq
```

Enviar ambos grupos y preparar la limpieza posterior de versiones anteriores:

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/refresh?language=es' \
  -H 'Content-Type: application/json' \
  -d '{
    "previousVersions": "removeTorrentAndFiles",
    "multipleHashes": "all",
    "batchSize": 100,
    "concurrency": 2,
    "interval": "100ms",
    "options": {"qbittorrent": {"category": "Epublibre"}}
  }' | jq
```

La categoría debe existir en qBittorrent. Para conservar los archivos antiguos,
usar `removeTorrent`; para conservar también los torrents, usar `keep` u omitir
`previousVersions`. No se borra nada durante el envío.

```bash
# Progreso del envío (COMPLETED no significa que hayan terminado las descargas).
curl -s 'http://192.168.2.2:8088/api/torrent/jobs/JOB_ID' | jq

# Plan del job combinado, incluidos los libros nuevos y las revisiones nuevas.
curl -s 'http://192.168.2.2:8088/api/torrent/updates/JOB_ID' | jq

# Cuando las nuevas revisiones estén descargadas, limpieza manual del job.
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates/JOB_ID/cleanup' | jq
```

La limpieza solo afecta a los registros anteriores guardados en el plan; los libros
nuevos no generan registros de borrado. Conserva todas las comprobaciones de
completitud, hashes y rutas. También sirve `POST /api/torrent/updates/cleanup`
para procesar todos los planes pendientes. No existe un endpoint separado
`/refresh/cleanup`.

Para trabajar con cada grupo por separado:

```bash
curl -s 'http://192.168.2.2:8088/api/torrent/books?selection=new&language=es&multipleHashes=all' | jq
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/books?selection=new&language=es' \
  -H 'Content-Type: application/json' -d '{"multipleHashes":"all"}' | jq

curl -s 'http://192.168.2.2:8088/api/torrent/updates?language=es&multipleHashes=all' | jq
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates?language=es' \
  -H 'Content-Type: application/json' \
  -d '{"previousVersions":"removeTorrent","multipleHashes":"all"}' | jq
```

El bulk normal `POST /api/torrent/books?language=es` conserva su comportamiento:
selecciona todos los libros en español, sin limitarse a novedades y sin preparar
limpieza de revisiones anteriores.

## Configuración pública de interfaz

`GET /api/ui/config` devuelve únicamente `{ "defaultLanguage": "auto" }` (o el
valor configurado con `EPLSYNC_UI_LANGUAGE` / `eplsync.ui.language`). Lleva
`Cache-Control: no-store`; no expone credenciales ni configuración del cliente
torrent. Con `auto`, el frontend detecta el idioma del navegador. Para códigos explícitos,
comprueba si dispone de esa traducción y usa inglés en caso contrario. El valor
por defecto del backend es `auto`.


## Directorio del catálogo

`GET /api/catalog/directory/{kind}` lista valores distintos de la base de datos.
`kind` admite `authors`, `languages`, `genres` o `years`. No consulta el cliente torrent.
Parámetros: `q` (búsqueda literal, máximo 512 caracteres), `page` (desde 0) y `size`
(10, 20, 50, 100, 200, 500 o 1000; predeterminado 20). Devuelve la estructura paginada
habitual con `items: [{"value":"..."}]` y `meta`. Los años se ordenan de mayor a
menor; las otras categorías, por su valor almacenado ascendente. Los idiomas se
convierten a su código ISO en la respuesta; la búsqueda compara el valor almacenado.
Autores y géneros compuestos se mantienen intactos, sin normalización. Los valores
nulos o vacíos se excluyen. Un tipo, página o tamaño inválido devuelve `400`.
