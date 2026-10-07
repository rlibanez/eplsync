# Referencia de la API de EPLsync

Todas las operaciones requieren sesión y los permisos documentados en [Seguridad](seguridad.md). Las escrituras requieren token CSRF; las excepciones públicas son `/api/auth/csrf`, `/api/auth/status`, `/api/auth/setup`, `/api/auth/login`, `/api/auth/register`.

La gestión de usuarios (`/api/security/users`) requiere `ADMIN`. `GET` incluye los permisos efectivos de cada cuenta; `POST` crea una cuenta y devuelve una contraseña temporal; `PUT /{id}` cambia rol, estado y excepciones de permisos. `DELETE /{id}` exige CSRF y el cuerpo `{"confirm":true}`: elimina la cuenta y sus excepciones, revoca sus sesiones y conserva los datos compartidos. El último administrador activo no se puede borrar.


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

19. [Eventos persistentes](#19-eventos-persistentes)

## 1. Convenciones generales

- En consultas GET, filtros y paginación van en la URL. En operaciones POST con
  simulación, todas las opciones van en el body JSON; los filtros torrent van en `filters`.
- Las opciones de envío van en un cuerpo JSON con `Content-Type: application/json`.
- Los campos opcionales omitidos o `null` heredan la configuración correspondiente.
  `dryRun` es obligatorio y nunca admite `null`.
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

### Simulación y ejecución

Las operaciones con simulación usan **POST con un body JSON y `dryRun` obligatorio**:
`true` calcula el resultado; `false` ejecuta. Omitirlo, enviarlo como `null`, texto o
número devuelve `400`. También se rechazan opciones desconocidas y parámetros en
la URL en estos endpoints:

- `/api/torrent/downloads/sync`
- `/api/catalog/covers/check` y `/api/catalog/covers/task`
- `/api/torrent/books`, `/api/torrent/updates` y `/api/torrent/refresh`
- `/api/catalog/import/run`

En selecciones torrent, los filtros se agrupan en `filters`; `status` y `sort` son
listas JSON. En portadas, las opciones son campos directos del body. Las cargas ZIP
usan multipart: `file` y `options`, una parte `application/json` con `{"dryRun":true}`
o `{"dryRun":false}`. No se admite el antiguo campo `mode`.

Los GET consultan recursos existentes. Aplicar/descartar/recalcular una previsualización
y preparar/confirmar una lista de ausentes conservan sus POST específicos y tokens:
no vuelven a seleccionar silenciosamente otros datos. Una simulación puede registrar
eventos o guardar un archivo temporal, pero no aplica los cambios de negocio.

Se han retirado los GET de simulación y los POST de importación `/preview` y `/update`;
no hay alias de compatibilidad. Usa `/run` con `dryRun`. El reemplazo del catálogo
`/import/reset` sigue siendo una operación distinta, sin simulación.

## 2. Importación del catálogo

| Método | Endpoint | Acción |
| --- | --- | --- |
| POST | `/api/catalog/import/reset` | Reemplaza el catálogo de libros con el CSV descargado. |
| POST | `/api/catalog/import/run` | `dryRun=true`: previsualiza; `false`: inserta y actualiza conservando ausentes. |

### Opciones de importación

`/run` recibe `source` (`URL` o `SAVED`) y `dryRun` en JSON. Para `URL`, `url` es
opcional y por defecto usa `eplsync.catalog.zip-url`. Para `SAVED`, requiere `archiveId`.
Solo al previsualizar una URL admite `includeDetails` (false), `page` (0) y `size` (50).
El endpoint separado `/reset` conserva su parámetro URL opcional `url`.

```bash
# Previsualizar cambios con detalle
curl -s -X POST 'http://192.168.2.2:8088/api/catalog/import/run' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"includeDetails":true,"page":0,"size":50,"source":"URL"}' | jq

# Actualizar el catálogo
curl -s -X POST 'http://192.168.2.2:8088/api/catalog/import/run' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"source":"URL"}' | jq

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

### Asistente de importación y ZIP guardado

`GET /api/catalog/import/source` devuelve la URL efectiva de `eplsync.catalog.zip-url`
y el único ZIP guardado, si existe:

```json
{
  "defaultUrl": "https://example.org/catalog.zip",
  "archive": {
    "id": "identificador-del-zip",
    "name": "catalog.zip",
    "sourceUrl": "https://example.org/catalog.zip",
    "storedAt": "2026-10-02T12:00:00Z",
    "expiresAt": "2026-10-03T12:00:00Z",
    "size": 35127296,
    "sha256": "sha256-del-zip",
    "csvName": "catalog.csv",
    "csvModifiedAt": "2026-10-02T04:00:00"
  }
}
```

`archive` es `null` si no hay archivo disponible; `sourceUrl` es `null` para cargas
locales. La fecha del CSV procede de su entrada en el ZIP, sin atribuirle zona horaria;
puede ser `null`. El SHA-256 del ZIP es distinto del SHA-256 del CSV en los metadatos.

`POST /api/catalog/import/run` acepta estos cuerpos JSON:

```json
{"source":"URL","url":"https://example.org/other.zip","dryRun":true}
```

```json
{"source":"SAVED","archiveId":"identificador-del-zip","dryRun":false}
```

- `dryRun` es obligatorio: `true` previsualiza y `false` actualiza. Ambos devuelven un `ImportResult`.
- Con `URL`, omitir `url` utiliza la URL configurada. Una URL personalizada afecta
  solo a esta operación; no modifica la configuración.
- Con `SAVED`, `archiveId` es obligatorio. Un ZIP caducado o sustituido devuelve
  `410` con `code: ARCHIVE_EXPIRED`; nunca se usa otro archivo silenciosamente.

Para cargas locales acepta `multipart/form-data`:

```bash
curl -X POST http://localhost:8088/api/catalog/import/run -F 'options={"dryRun":true};type=application/json' -F 'file=@catalog.zip;type=application/zip'
```

Admite archivos no vacíos con extensión `.zip`, hasta 128 MiB por defecto
(`spring.servlet.multipart.max-file-size`). El ZIP debe contener un único CSV.
Superar el límite devuelve `413` con `code: ZIP_TOO_LARGE`.

Se conserva **un único ZIP**. Iniciar una descarga o carga nueva elimina el anterior
y su previsualización, aunque la nueva operación falle. Elegir una opción del
asistente o cancelarlo no elimina nada. Un ZIP válido puede mantenerse aunque falle
el procesamiento de su CSV. Los CSV extraídos se borran al terminar cada operación.

### Aplicar o descartar la previsualización

Previsualizar conserva el ZIP y añade al resumen:

```json
"preview": {
  "token": "identificador-opaco",
  "expiresAt": "2026-10-03T12:00:00Z",
  "sourceModifiedAt": "2026-10-02T04:00:00"
}
```

`POST /api/catalog/import/run` también mantiene este comportamiento; con
`includeDetails=true`, estos datos están en `summary.preview`. No modifica libros
ni metadatos; registra el inicio y resultado en Eventos.

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/catalog/import/preview/{token}` | Recupera el resumen guardado sin recalcular. |
| POST | `/api/catalog/import/preview/apply` | Actualiza desde el ZIP previsualizado, sin descargar de nuevo. |
| POST | `/api/catalog/import/preview/refresh` | Recalcula desde el mismo ZIP sin ampliar su caducidad. |
| POST | `/api/catalog/import/preview/discard` | Descarta solo la previsualización; devuelve `204` y conserva el ZIP. |

Los tres POST reciben `{"token":"identificador-opaco"}` en JSON. Aplicar consume la
previsualización pero conserva el ZIP. Un fallo revierte la transacción y mantiene
el archivo disponible. Dos aplicaciones simultáneas del mismo token no importan
dos veces. Descartar es idempotente.

Si el catálogo o sus metadatos cambiaron desde la previsualización, Aplicar devuelve
`409` con `PREVIEW_STALE`: hay que Recalcular y revisar antes de confirmar. Un ZIP
alterado devuelve `409` con `PREVIEW_FILE_CHANGED`. Un token caducado, sustituido o
consumido devuelve `410` con `PREVIEW_EXPIRED`.

Configuración:

```yaml
eplsync:
  catalog:
    import:
      retention: 24h
```

La retención se fija al guardar el ZIP, entre más de cero y siete días. Reutilizar,
importar o descartar **no renueva** su vencimiento. La limpieza se ejecuta cada minuto
y al consultar o usar el archivo. El reinicio completo elimina ZIP y previsualización.

El archivo y su descriptor se guardan en `${java.io.tmpdir}/eplsync-catalog-import`,
sin configurar directorio ni número de archivos. Sobreviven al reinicio del proceso
si el directorio sigue disponible; no se garantiza su conservación al recrear el
contenedor. Este directorio debe ser exclusivo de una instancia.

En Ajustes → Base de datos, «Importar catálogo» abre el asistente de origen y acción.
Al iniciar, se cierra; progreso y resultado aparecen en la sección de importación.
La previsualización ofrece Actualizar catálogo, Descartar y, si procede, Recalcular.
La X equivale a Descartar y conserva el ZIP. La pestaña guarda el token para recuperar
el resumen al recargar. El ZIP vuelve a estar disponible al abrir el asistente, con
fecha, tamaño y SHA-256 copiable.

`/run` con `source="URL"` y `/reset` descargan desde la URL solicitada y
sustituyen el ZIP guardado. `/missing/preview` descarga temporalmente su fuente para
analizar ausentes y no sustituye el ZIP del asistente.

### Reiniciar toda la base de datos

`POST /api/maintenance/reset` requiere ADMIN y un cuerpo JSON `{"confirm":true}`.
La opción `eraseUsersAndSettings=true` elimina también cuentas y toda la configuración,
requiere `fullResetConfirmation="BORRAR TODO"` e invalida las sesiones.
Consulta [Seguridad](seguridad.md) para inicializar de nuevo el administrador.
Vacía las ocho tablas de datos: libros, metadatos del catálogo, descargas,
trabajos, elementos de trabajos, planes de actualización, registros de limpieza y eventos.
**No descarga ni importa el CSV**. El frontend utiliza este endpoint; no utiliza
`/api/catalog/import/reset`, que continúa reemplazando solo el catálogo desde el CSV.

Por defecto conserva las cuentas, la configuración, el archivo SQLite y su esquema, los logs y las preferencias
del navegador. No modifica torrents ni archivos del cliente. Todos los borrados se
realizan en una transacción: si uno falla, se revierten todos.

Devuelve `200` con `success` y los contadores `catalogBooks`, `downloads`, `jobs`,
`jobItems`, `updatePlans`, `cleanupRecords`, `metadataRecords` y `events`.
La confirmación ausente o distinta de `true` produce `400`; las peticiones API,
comprobaciones de portadas o envíos en curso pueden impedir el reinicio con `409`.
Los trabajos en pausa o en cola también se borran al confirmar el reinicio.

El propio reinicio no genera un evento persistente, para dejar la tabla vacía:
se registra en `eplsync.log` y la interfaz muestra una notificación temporal.
El canal SSE emite `database-reset` para invalidar los datos de los navegadores
conectados. Se conserva la secuencia interna de SQLite para no reutilizar IDs de eventos.

### Libros ausentes del CSV

La previsualización y la actualización incluyen `missingBooks`: número de libros
locales ausentes del CSV. `metadata.missingRows` conserva el resultado del último
CSV importado (es histórico, no un recuento en tiempo real). Si hay filas erróneas,
el valor es `null`, pues no se puede determinar con seguridad qué libros faltan.

| Método | Endpoint | Uso |
| --- | --- | --- |
| POST | `/api/catalog/import/missing/preview` | Descarga el CSV y prepara una lista de libros ausentes, sin modificar datos. |
| GET | `/api/catalog/import/missing/{token}?page=0&size=50` | Consulta la lista ya calculada, sin descargar de nuevo. |
| POST | `/api/catalog/import/missing/delete` | Elimina los libros de la lista confirmada. |

La previsualización devuelve `token`, `expiresAt`, `total`, `page`, `size` e `items`
con `eplId`, `title` y `revision`. Se conservan como máximo ocho listas en memoria,
durante 15 minutos; reiniciar la aplicación o vaciar la base de datos las invalida.
Las páginas admiten entre 1 y 100 elementos.

La confirmación requiere el cuerpo `{"token":"…","confirm":true}` y devuelve
`{"deleted":N}`. No vuelve a descargar el CSV: solo elimina los libros revisados.
Un CSV vacío o con errores impide preparar la lista. Una lista caducada, cambios
en el catálogo o trabajos pendientes relacionados producen `409` y requieren revisión.
La eliminación es transaccional y conserva el historial de descargas y trabajos,
además de los torrents y archivos del cliente. Queda registrada en Eventos.

## 3. Consulta de libros y filtros compartidos

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/catalog/books/{eplId}` | Un objeto; `404` si el libro no existe. |
| GET | `/api/catalog/books` | Listado filtrado, siempre paginado. |

```bash
curl -s 'http://192.168.2.2:8088/api/catalog/books/32' | jq

curl -s 'http://192.168.2.2:8088/api/catalog/books?eplId=32' | jq

curl -s \
  'http://192.168.2.2:8088/api/catalog/books?language=es&page=0&size=100&sort=eplId,asc' | jq
```

En `GET /api/catalog/books`, los nombres de los parámetros distinguen mayúsculas y
minúsculas, con una excepción: `eplid` se acepta como alias de `eplId` (nombre oficial).
Se pueden repetir ambos nombres: sus identificadores se combinan sin duplicados.
Los valores vacíos o inválidos devuelven `400`. Los parámetros desconocidos también devuelven
`400` para evitar listar todo el catálogo por un filtro mal escrito.
La consulta por filtro devuelve `items` y `meta`; la ruta `/api/catalog/books/32` devuelve un objeto.

Los resultados incluyen `download.items`, con `id`, `revision`, `status` y
`completed` de los registros de descarga asociados. Es información guardada en
EPLsync; consultar un libro no ejecuta un sync.

El filtro de catálogo `revision` permite seleccionar una revisión numérica exacta
(por ejemplo, `GET /api/catalog/books?revision=1.1`) y combinarla con los demás filtros.
Debe ser un número finito mayor o igual a cero.

En la tabla del catálogo, los valores de las columnas (excepto el título, que abre
el detalle) permiten aplicar filtros rápidos. Conservan los demás filtros,
ordenación y tamaño de página, y vuelven a la primera página. Los géneros se
seleccionan individualmente. Años y fechas se convierten en rangos de un solo día
o año; la incorporación usa el día de la zona horaria del navegador. Los campos
de texto conservan la búsqueda por coincidencia parcial del buscador avanzado.

### Selecciones de filas del catálogo

Además de los criterios de búsqueda, `selectedIds` admite una lista explícita de
EPL ID y `excludedIds` excluye identificadores. Ambos se combinan mediante AND
con los demás filtros. Una lista `selectedIds: []` no selecciona ningún libro;
los identificadores deben ser positivos. No tienen el límite de 100 alternativas
de los campos del buscador, para permitir selecciones de varias páginas.

```json
{"dryRun":false,"filters":{"selectedIds":[32,14936]},"options":{}}
```

El cuerpo anterior crea un trabajo con POST `/api/torrent/books`. Para todos los
resultados salvo algunas filas, se envían los filtros y `excludedIds`, junto con
`all:true`. No se utiliza la paginación visible como límite del trabajo.

POST `/api/catalog/magnets/export` acepta `{"filters":{"selectedIds":[32,14936]}}`
o filtros con exclusiones. Es una consulta de exportación de solo lectura con
cuerpo JSON para evitar URLs demasiado largas; no admite ni necesita `dryRun`.
Devuelve texto UTF-8 con un magnet por línea, todos los hashes de cada libro y
sin duplicados por hash. La interfaz elige el nombre y no fuerza extensión.
La variante GET de exportación por query sigue siendo una consulta de lectura.

### Filtros compartidos

Estos filtros funcionan en:

- `GET /api/catalog/books`
- `GET /api/catalog/magnets`
- `GET /api/catalog/magnets/export`
- `POST /api/torrent/books` (selección general o `selection="new"` en JSON)
- `POST /api/torrent/updates`
- `POST /api/torrent/refresh`

| Parámetro | Significado / valores |
| --- | --- |
| `eplId` | Identificador exacto, entero positivo de tipo `Long`. |
| `revision` | Revisión numérica exacta, finita y mayor o igual a cero. |
| `author` | El autor contiene el texto indicado; hasta 255 caracteres. |
| `title` | El título contiene el texto indicado; hasta 512 caracteres. |
| `genres` | Los géneros contienen el texto indicado; hasta 512 caracteres. |
| `collection` | La colección contiene el texto indicado; hasta 255 caracteres. |
| `publicationYear` | Año exacto, entero menor o igual a `3000`; admite años negativos (anteriores a nuestra era). |
| `pagesFrom` | Número mínimo de páginas, incluido; entero mayor o igual a cero. |
| `pagesTo` | Número máximo de páginas, incluido; entero mayor o igual a cero. Debe ser >= `pagesFrom` si se indican ambos. Límites iguales buscan coincidencias exactas; con un solo límite el otro queda abierto. Los libros sin número de páginas no coinciden con un rango. |
| `publicationYearFrom` | Año mínimo, incluido; entero menor o igual a `3000`; admite años negativos (anteriores a nuestra era). |
| `publicationYearTo` | Año máximo, incluido; entero menor o igual a `3000`; admite años negativos (anteriores a nuestra era). |
| `language` | `es`, `en`, `ca`, `gl`, `eu`, `fr`, `it`, `pt`, `de`, `eo`, `sv`, `other`. |
| `publicationStatus` | `PUBLISHED`, `UPDATED`, `UNKNOWN`. |
| `status` | `DISPONIBLE`, `VERIFICADO`, `DESCONOCIDO`. Puede repetirse para seleccionar varios. |
| `publicationDate` | Fecha exacta, `YYYY-MM-DD`. |
| `publicationDateFrom` | Fecha mínima, incluida. |
| `publicationDateTo` | Fecha máxima, incluida. |

Los campos se combinan mediante **AND** y los valores de un mismo campo mediante **OR**.
`title`, `author`, `collection`, `genres`, `eplId`, `revision`, `language`,
`publicationStatus` y `status` admiten varios valores. En GET se repite el parámetro
(`author=Asimov&author=Sanderson&language=es&language=en`); en los filtros JSON de
POST se usan arrays (`"author":["Asimov","Sanderson"]`). También se admite un
valor escalar en JSON. Las comas en textos no separan criterios. Cada uno de los
nuevos campos múltiples admite hasta 100 valores; las longitudes de texto de la
tabla se aplican a cada valor. Los rangos de fechas y años siguen siendo únicos.
 `language` también admite nombres del enum como `ESPANOL`, sin
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
| `size` | Entre 1 y 1000; predeterminado: 20. |
| `sort` | `campo,asc` o `campo,desc`; se puede repetir. |

- Siempre devuelve `items` y `meta`; sin parámetros utiliza `page=0` y `size=20`.
- Tamaños superiores a 1000 y desplazamientos superiores a 2147483647 se rechazan con HTTP 400.
- Se permiten hasta 8 criterios de ordenación, sobre campos reconocidos.
- Al ordenar por `insertDate` («Añadido a EPL Sync»), se comparan minutos completos,
  tanto en ascendente como en descendente. Los siguientes criterios resuelven
  los empates dentro del minuto; si no se especifica `eplId`, se añade ascendente
  como último desempate estable. La ordenación se realiza antes de paginar.
  El mismo criterio se aplica al ordenar los magnet links del catálogo.
  La fecha almacenada y la devuelta por la API conservan segundos y milisegundos;
  los filtros temporales y las fechas de eventos y trabajos no cambian.
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
rating, votesCount, links, coverUrl
```

`download` es información añadida a la respuesta, no un campo persistido para ordenar.

El detalle y los listados incluyen `coverUrl`: URL importada de la columna
opcional `Portada` del CSV, o `null` si no hay valor. La API no calcula una URL
alternativa ni descarga imágenes. El frontend usa la portada de ePubLibre por ID
cuando `coverUrl` está ausente.

También se expone `coverAvailable` (`true`, `false` o `null`). El frontend usa
ePubLibre si es `false`; conserva la URL original en la respuesta y en la BD.

### Comprobación de portadas

| Método | Endpoint | Resultado |
| --- | --- | --- |
| POST | `/api/catalog/covers/check` | `dryRun=true`: simula; `false`: guarda disponibilidad concluyente. |

Parámetros compartidos: `eplId` opcional, `size` opcional (entero positivo),
`afterId=0` y `onlyUnchecked=true`. **Sin `size` recorre todo el catálogo seleccionado**,
sin límite total. Para comprobar también estados ya guardados, usar
`onlyUnchecked=false`. Si se indica `size`, limita los libros comprobados y permite
continuar con `afterId=nextAfterId` mientras `hasMore=true`.

Ejemplo completo sin escrituras, mostrando solo resultados no encontrados:
`POST /api/catalog/covers/check` con `{"dryRun":true,"onlyUnchecked":false,"coverAvailable":false}`.

La respuesta llega al terminar; una comprobación completa puede requerir una
conexión HTTP de larga duración. Con `dryRun=false` realiza una nueva comprobación y guarda al
final; no aplica una instantánea de una simulación anterior. HTTP 409 indica que otra
comprobación está en curso.

En ambos modos: `coverAvailable=true|false` filtra `items` por el resultado de la
comprobación actual (no por el estado almacenado). Sin ese parámetro muestra todos.
Los contadores y `nextAfterId`/`hasMore` corresponden al recorrido completo, aunque no
haya coincidencias. `size` limita las comprobaciones, no los resultados filtrados.

HTTP 200 con tipo imagen produce `true`; HTTP 404/410 produce `false`. Otros
resultados no modifican el estado. No se borra `coverUrl` ni se guarda una fecha.
La respuesta detalla estados previos, resultados, cambios propuestos y aplicados.
Ver [comportamiento y ejemplos](catalogo.md#comprobar-disponibilidad-de-portadas).

Para la interfaz de **Ajustes → Portadas**, `GET /api/catalog/covers/config` expone
los valores iniciales; `POST /api/catalog/covers/task` inicia una tarea completa
en segundo plano y `GET /api/catalog/covers/task` consulta su estado. El POST acepta
`dryRun` obligatorio y `onlyUnchecked` (opcional, `false` por defecto en este endpoint de tareas)
y `options` con `connectTimeoutMs`, `requestTimeoutMs`, `batchTimeoutMs`
y `concurrency`, sin modificar la configuración global. La última tarea se conserva
en memoria hasta reiniciar el servidor. Véase [gestión desde la interfaz](catalogo.md#gestión-de-portadas-desde-la-interfaz).

`POST /api/catalog/covers/{eplId}/alternative`, con `{"expectedCoverUrl":"URL comprobada"}`,
permite marcar explícitamente la portada como no disponible. Conserva su URL y
rechaza con HTTP 409 una URL que haya cambiado desde la comprobación.

## 4. Magnets y exportación

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/catalog/books/{eplId}/magnets` | Array de magnets de un libro. |
| GET | `/api/catalog/magnets` | Magnets filtrados, siempre paginados (`items` y `meta`). |
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
La comprobación explícita también funciona con la integración deshabilitada: puede devolver `enabled=false` y `connected=true`. No activa la integración ni permite enviar descargas. Comprueba los ajustes guardados, no los cambios pendientes del formulario.

La URL y las credenciales del cliente proceden de la configuración de EPLsync,
no de esta petición.

### Comprobar los valores del formulario sin guardarlos

`POST /api/settings/torrent/connection` acepta un objeto JSON con los cambios del formulario, con las mismas claves y validación que `PUT /api/settings/torrent`. Requiere sesión, CSRF y `SETTINGS_MANAGE`; cambiar la URL o la autenticación para la prueba requiere además ADMIN.

Los campos omitidos conservan su valor guardado. Si cambia la URL, solo se usan las credenciales proporcionadas expresamente para ese nuevo destino. La comprobación no guarda ajustes ni credenciales, no activa la integración y no necesita `EPLSYNC_SECRET_KEY` para probar credenciales sin persistirlas. El botón «Comprobar conexión» utiliza este endpoint.

### Categorías del cliente

`GET /api/torrent/client/categories` consulta las categorías del cliente configurado.
Devuelve un array de nombres ordenados, sin rutas ni credenciales:

```json
["Libros", "Revistas"]
```

No crea ni modifica categorías. Una lista vacía indica que no hay categorías;
los fallos de conexión o autenticación se devuelven como errores, no como listas
vacías. La respuesta no se almacena en caché. Con torrent deshabilitado devuelve
409; un adaptador que no soporte categorías devuelve 422.

El popup de envío consulta este endpoint al abrirse y selecciona
`torrent.qbittorrent.download.category` si existe en la lista. En caso contrario
selecciona «Sin categoría» y envía `category: ""`. La configuración global y el
comportamiento de los envíos directos por API no cambian.

## 6. Envío individual

```http
POST /api/torrent/books/{eplId}
```

Este endpoint crea un trabajo de un libro; no envía directamente al cliente.
`dryRun` es obligatorio. Las opciones de envío van en `options`; si se omiten,
se resuelven y guardan los valores efectivos del servidor para ese trabajo.

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/books/32' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false}' | jq
```

Ejemplo con opciones personalizadas:

```json
{
  "dryRun": false,
  "options": {
    "hash": "HASH_DEL_LIBRO",
    "start": true,
    "savePath": "/downloads/epublibre",
    "rename": {"enabled": true, "pattern": "{author} - {title} [{eplId}] (r{revision})"},
    "qbittorrent": {"category": "Epublibre", "tags": ["EPLsync", "{language}"], "autoManagement": false}
  }
}
```

| Campo de `options` | Descripción |
| --- | --- |
| `hash` | Hash del libro. Obligatorio si hay varios; hexadecimal de 40 caracteres o Base32 de 32. |
| `start` | Inicia la descarga al añadir, o la deja detenida. |
| `savePath` | Ruta dentro del cliente. No vacía requiere gestión automática desactivada; `""` usa el destino del cliente. |
| `rename.enabled`, `rename.pattern` | Renombrado del torrent; no renombra archivos. |
| `qbittorrent.category` | Categoría existente; `""` envía sin categoría. |
| `qbittorrent.tags` | Etiquetas y patrones; `[]` envía sin etiquetas. |
| `qbittorrent.autoManagement` | El cliente determina el destino según su configuración/categoría. |

`dryRun:true` valida la preparación y devuelve un resumen (`selectedBooks`,
`selectedItems`, `skipped`) sin crear trabajo ni contactar con el cliente. No predice
si el cliente aceptará el torrent. `dryRun:false` devuelve `202`, una vista de trabajo
con `jobId` y cabecera `Location`. El resultado del envío se consulta en ese trabajo.

### Valores predeterminados de envío

`GET /api/torrent/options` devuelve `start`, `savePath`, `autoManagement`,
`rename`, `category`, `tags`, `concurrency`, `batchSize`, `interval` y `multipleHashes`.
Son los valores efectivos de EPL Sync, incluidas las variables de entorno; no las
preferencias consultadas a qBittorrent. No expone URL de conexión ni credenciales.
La respuesta usa `Cache-Control: no-store`.

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

Aceptar el trabajo no significa que el torrent ya esté enviado o descargado. Si ya
existe en el cliente, su elemento terminará como `ALREADY_EXISTS` sin cambiarlo.

## 7. Envío múltiple o bulk

```http
POST /api/torrent/books
```

Admite los [filtros compartidos](#3-consulta-de-libros-y-filtros-compartidos) y:

| Campo del body JSON | Significado |
| --- | --- |
| `page`, `size` | Seleccionan una página de **libros**. Defaults parciales: `0` y `20`. |
| `sort` | Orden de selección; se añade `eplId` como desempate cuando falta. |
| `all` | `true` permite seleccionar todo sin filtros ni paginación. Predeterminado: `false`. |

Sin `page`/`size`, selecciona todos los libros que cumplan los filtros.
Si tampoco hay filtros, se requiere `all=true`.

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"batchSize":100,"concurrency":4,"interval":"100ms","multipleHashes":"all","options":{"start":true,"qbittorrent":{"category":"Epublibre","tags":["EPLsync","{language}"],"autoManagement":true}},"filters":{"language":"es"},"page":0,"size":1000,"sort":["eplId,asc"]}' | jq
```

Con `dryRun=true`, devuelve `200` con `dryRun`, `applied=false`, `selectedBooks`,
`selectedItems`, `skipped` e `items`. `includeDetails=true` incluye en `items` cada
registro previsto (sin ID persistente), con `eplId`, `hash`, `status` y `message`.
La preparación comparte selección, deduplicación y validación con la ejecución;
no crea trabajos, no guarda items ni envía torrents. Por defecto `items` está vacío.

### Opciones del cuerpo

| Campo | Valores / significado |
| --- | --- |
| `batchSize` | `1–1000`; elementos cargados por lote desde SQLite.  |
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
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"all":true}' | jq
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

Se ordenan por creación descendente, con desempate por ID. Véase la sección de ordenación del listado de trabajos para los campos de `sort`.

### Consultar elementos

| Parámetro | Predeterminado / valores |
| --- | --- |
| `page` | `0`. |
| `size` | `50`. |
| `sort` | Repetible, hasta 8 criterios por prioridad; predeterminado: `position,asc`; campos: `position`, `title`, `eplId`, `revision`, `hash`, `status`, `attempts`, `message`. Dirección: `asc` o `desc`. |
| `status` | Uno o varios estados separados por comas; omitido: todos. |

```text
PENDING, IN_FLIGHT, ACCEPTED, ALREADY_EXISTS, SKIPPED, FAILED, CANCELLED
```

```bash
curl -s \
  'http://192.168.2.2:8088/api/torrent/jobs/JOB_ID/items?status=FAILED,SKIPPED&size=1000' | jq
```

Cada elemento contiene `id`, `eplId`, `hash`, `status`, `attempts`, `message`, `title`, `coverUrl`, `coverAvailable` y `revision`. La revisión se guarda al preparar el envío y no cambia al actualizar el catálogo; en registros anteriores sin revisión guardada puede ser nula. Los metadatos del libro corresponden al catálogo actual; si ya no existe, se conservan el elemento y su EPL ID.
Se filtra y ordena antes de paginar; el orden predeterminado es la posición del elemento en el job.

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
id, eplId, title, revision, hash, status, origin, client, clientInstanceId, lastError,
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
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/downloads/sync' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"includeDetails":true}' | jq
```

`dryRun` es obligatorio y debe ser booleano JSON. `includeDetails` es opcional,
booleano y por defecto `false`. No se admiten opciones en la URL, campos desconocidos,
valores nulos ni cadenas en lugar de booleanos (`400`). GET no está soportado (`405`).
Para aplicar, enviar el mismo cuerpo con `"dryRun":false`.

Ambos modos consultan el estado actual del cliente y registran inicio y resultado
en Eventos (SYNC_PREVIEW para simular, SYNC para ejecutar), incluidos los fallos.
La simulación no modifica registros ni fechas de descargas. La ejecución guarda
los cambios en una transacción. No añade,
renombra ni elimina torrents. Una ejecución posterior recalcula el resultado: no
aplica una instantánea de una simulación anterior. Conflictos con otra sincronización
o envíos en curso devuelven `409`.

La respuesta contiene `client`, `clientInstanceId`, `checkedAt` (UTC), `dryRun`,
`applied` y tres grupos de contadores:

| Grupo | Campos y significado |
| --- | --- |
| `remote` | `total`, `matched`, `ignored`: torrents únicos recibidos, relacionados y ajenos. `total = matched + ignored`. |
| `records` | `checked`, `created`, `updated`, `unchanged`: registros existentes y nuevos propuestos. `checked = created + updated + unchanged`. |
| `outcomes` | `newlyCompleted`, `notFound`, `newlyNotFound`: finalización detectada por primera vez, estado resultante NOT_FOUND y transición nueva a NOT_FOUND. |

`updated` considera cambios de estado, finalización y error. Cambiar únicamente
`lastCheckedAt` o `lastSeenAt` no cuenta como cambio funcional. `outcomes` se solapa
con las acciones; no debe sumarse a `records`. Un torrent puede relacionarse con
varios registros. Los contadores describen lo calculado en ambos modos;
`applied=true` confirma que la ejecución terminó correctamente.

Con `includeDetails=true` se incluyen dos listas completas, sin paginación del servidor:

- `items`: todos los registros evaluados, incluidos los sin cambios. Campos:
  `downloadId`, `eplId`, `title`, `hash`, `action` (`CREATE`, `UPDATE`, `UNCHANGED`),
  `previousStatus`, `resultingStatus`, `foundInClient`, `changedFields`,
  `newlyCompleted`, `newlyNotFound`, `previousCompletedAt`, `resultingCompletedAt`,
  `previousError`, `resultingError`. La creación simulada tiene `downloadId=null`;
  el título puede faltar si el libro ya no está en el catálogo. `changedFields`
  contiene cambios de registros existentes; una creación usa `action=CREATE`.
- `ignoredTorrents`: `hash`, `name` (si está disponible), `reason=NO_CATALOG_MATCH`.

Sin detalle ambas listas se omiten. DESCARGAS → Estado muestra el resumen y permite
filtrar, buscar y paginar estas listas localmente, sin repetir consultas al cliente.
El último resultado y los filtros de su detalle se conservan en memoria al navegar
entre vistas, hasta cerrarlo con la X, sustituirlo por otro resultado o recargar la
página. No se almacena un historial de resultados de sincronización.

## 12. Previsualización de actualizaciones

```http
POST /api/torrent/updates
```

| Parámetro | Descripción / predeterminado |
| --- | --- |
| `filters.eplId` | Limita la búsqueda a un libro; omitido: todos los candidatos. |
| `includeNotFound` | `false`; con `true` incluye historial elegible desaparecido del cliente. |
| `multipleHashes` | `all`, `skip`, `first`; omitido: configuración bulk. |
| `page` | `0`. |
| `size` | `50`. |

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"multipleHashes":"all","page":0,"size":1000}' | jq

curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"filters":{"eplId":1234}}' | jq
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

### Cuerpo JSON obligatorio

`dryRun=false` crea el trabajo. Los filtros van en `filters`; `includeNotFound`
es opcional y vale `false` por defecto. No se admiten parámetros en la URL.

```json
{
  "dryRun": false,
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

`batchSize`, `concurrency`, `interval`, `multipleHashes` y
`options` tienen el significado y límites del bulk. `options.hash` no se admite.

| `previousVersions` | Política de limpieza posterior |
| --- | --- |
| `keep` | Predeterminada: conservar todo. |
| `removeTorrent` | Permitir eliminar solo el torrent anterior. |
| `removeTorrentAndFiles` | Permitir eliminar el torrent anterior y sus archivos. |

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"previousVersions":"removeTorrent","multipleHashes":"all","filters":{"eplId":1234}}' | jq
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

> Los planes creados mediante estas rutas se conservan para limpieza explícita. La sincronización manual también comprueba pendientes autorizados; los envíos desde Actualizaciones pueden habilitar la limpieza periódica.
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
| GET | `/api/events` |
| GET | `/api/events/stream` |
| GET | `/api/events/retention` |
| GET | `/api/events/unread` |
| POST | `/api/events/delete` |
| POST | `/api/catalog/import/reset` |
| POST | `/api/maintenance/reset` |
| POST | `/api/catalog/import/run` |
| GET | `/api/catalog/books` |
| GET | `/api/catalog/books/{eplId}` |
| GET | `/api/catalog/books/{eplId}/magnets` |
| GET | `/api/catalog/magnets` |
| GET | `/api/catalog/magnets/export` |
| POST | `/api/catalog/books/{eplId}/torrents/{hash}/rename` |
| GET | `/api/torrent/client/connection` |
| POST | `/api/torrent/books/{eplId}` |
| POST | `/api/torrent/books` (bulk normal o `selection=new`) |
| POST | `/api/torrent/books` |
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
| POST | `/api/torrent/updates` |
| GET | `/api/torrent/updates/{jobId}` |
| POST | `/api/torrent/updates/{jobId}/cleanup` |
| POST | `/api/torrent/updates/cleanup` |
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
| POST | `/api/torrent/books` | `selection="new", dryRun=true`: previsualiza libros sin historial en el cliente actual. |
| POST | `/api/torrent/books` | `selection="new", dryRun=false`: crea un job solo con esos libros nuevos. |
| POST | `/api/torrent/updates` | `dryRun=true`: previsualiza revisiones superiores de libros gestionados. |
| POST | `/api/torrent/updates` | `dryRun=false`: crea un job solo con esas revisiones superiores. |
| POST | `/api/torrent/refresh` | `dryRun=true`: previsualiza la unión de novedades y revisiones superiores. |
| POST | `/api/torrent/refresh` | `dryRun=false`: crea **un único job** con ambos grupos. |

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

| Campo del body | Uso |
| --- | --- |
| `dryRun` | Obligatorio: `true` previsualiza, `false` crea el trabajo. |
| `filters` | Objeto con los filtros de `CatalogBookFilter`. |
| `selection` | `"new"` en `/books` para seleccionar solo novedades. |
| `includeNotFound` | Solo `/updates` y `/refresh`; predeterminado `false`. |
| `multipleHashes` | `all`, `first`, `skip`; por defecto configuración del servidor. |
| `page`, `size` | Solo simulación de candidatos: `0`, `50` por defecto. |

La simulación devuelve `items` y `meta`. Cada candidato contiene `eplId`, `title`,
`catalogRevision`, `existingDownloads` y `targetHashes`. Las novedades tienen
`existingDownloads` vacío. No consulta ni modifica torrents del cliente.

La ejecución selecciona **todos los candidatos filtrados**, sin paginación y sin
necesitar `all=true`. Recibe `previousVersions` (solo updates/refresh), `multipleHashes`,
`batchSize`, `concurrency`, `interval` y `options`; los valores omitidos heredan el servidor.
`options.hash` no se admite. La política anterior predeterminada es `keep`.

Con `dryRun=false` devuelve `202`, resumen y `Location: /api/torrent/jobs/{jobId}`.
Los recursos de progreso, pausa, reanudación y cancelación no cambian. Previsualizar
no reserva libros; al ejecutar se vuelve a evaluar la selección. No se reservan
libros que ya tengan un envío pendiente; sin candidatos se crea un job vacío completado.

### Flujo recomendado: mantener una biblioteca en español

Después de importar el CSV, previsualizar novedades y revisiones nuevas juntas:

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/refresh' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"filters":{"language":"es"},"multipleHashes":"all","size":1000}' | jq
```

Enviar ambos grupos y preparar la limpieza posterior de versiones anteriores:

```bash
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/refresh' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"previousVersions":"removeTorrentAndFiles","multipleHashes":"all","batchSize":100,"concurrency":2,"interval":"100ms","options":{"qbittorrent":{"category":"Epublibre"}},"filters":{"language":"es"}}' | jq
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
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"selection":"new","filters":{"language":"es"},"multipleHashes":"all"}' | jq
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/books' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"multipleHashes":"all","selection":"new","filters":{"language":"es"}}' | jq

curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"filters":{"language":"es"},"multipleHashes":"all"}' | jq
curl -s -X POST 'http://192.168.2.2:8088/api/torrent/updates' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"previousVersions":"removeTorrent","multipleHashes":"all","filters":{"language":"es"}}' | jq
```

El bulk normal `POST /api/torrent/books` conserva su comportamiento:
selecciona todos los libros en español, sin limitarse a novedades y sin preparar
limpieza de revisiones anteriores.


## Directorio del catálogo

`GET /api/catalog/directory/{kind}` lista valores distintos de la base de datos.
`kind` admite `authors`, `collections`, `languages`, `genres` o `years`. No consulta el cliente torrent.
Parámetros: `q` (búsqueda literal, máximo 512 caracteres), `page` (desde 0) y `size`
(10, 20, 50, 100, 200, 500 o 1000; predeterminado 20). Devuelve la estructura paginada
habitual con `items` y `meta`. Autores, colecciones y géneros incluyen
`{"value":"...","initial":"A"}` y admiten el parámetro opcional `initial`
(`A`–`Z`, `Ñ` o `#`; enviar `%23` para `#`). Este filtro se combina con `q` antes
de paginar. Las vocales acentuadas se agrupan bajo su letra, `Ñ` tiene grupo propio
y `#` reúne las demás iniciales, incluidos números y símbolos. La búsqueda en estas
tres categorías ignora mayúsculas y acentos, pero distingue `N` de `Ñ`.
Se ordenan por inicial y alfabéticamente dentro de cada grupo.
Los años se ordenan de menor a mayor; los idiomas, por su valor almacenado ascendente.
En `GET /api/catalog/directory/years`, el parámetro opcional `century` prefiltra antes de paginar: `0` incluye años ≤ 0; `1`–`21` seleccionan siglos (I: 1–100; XXI: 2001–2100). Se combina con `q`; omitirlo muestra todos los años. Solo se admite en el directorio de años.
Los idiomas se
convierten a su código ISO en la respuesta; la búsqueda compara el valor almacenado.
Los autores se separan por `&` y los géneros por comas, eliminando espacios y
duplicados; las colecciones se conservan completas. Los campos originales de los
libros no se modifican. Los valores nulos o vacíos se excluyen. Un tipo, inicial,
página o tamaño inválido devuelve `400`; idiomas y años no admiten `initial`.

### Sugerencias de búsqueda

`GET /api/catalog/suggestions/{kind}?q=...&offset=0` devuelve sugerencias de texto
para `titles`, `authors`, `collections` o `genres`. Busca coincidencias parciales ignorando
mayúsculas y acentos (distingue `N` de `Ñ`). Requiere al menos dos caracteres;
con menos devuelve una lista vacía. `q` admite hasta 512 caracteres y `offset`
es un desplazamiento no negativo, por defecto 0.

Devuelve `items` (hasta 20 nombres), `total` (total de coincidencias) y
`nextOffset` (desplazamiento para continuar, o `null` al finalizar). No devuelve
libros ni portadas. Los autores y géneros se separan como en el directorio.
Los títulos y las colecciones se conservan completos, sin dividirlos por signos.
Los cuatro listados se cargan en memoria con su primera consulta y se invalidan al confirmar una
importación, eliminar libros ausentes o reiniciar la base de datos. La siguiente
consulta reconstruye el listado que necesite con los datos actuales.

En Catálogo y Directorio, el desplegable consulta tras una breve pausa al escribir
y carga más resultados al desplazarse. Permite seguir introduciendo texto libre.
Seleccionar una sugerencia no ejecuta la búsqueda: en Catálogo añade un criterio;
en Directorio rellena el campo.

### Rango de incorporación al catálogo

El filtro compartido admite `insertDateFrom` (instante ISO-8601 inclusivo) y
`insertDateBefore` (instante ISO-8601 exclusivo). Los rangos invertidos de años,
fechas de publicación e incorporación devuelven `400`.

La búsqueda avanzada del frontend está plegada por defecto e indica si hay filtros
activos. Ofrece título, autor, EPL Id, género, colección, idioma, estados y rangos
para año de publicación, publicación en EPL e incorporación a EPL Sync. Un inicio
sin fin busca hasta hoy; un fin sin inicio no impone límite inferior. Los días de
incorporación se convierten desde la zona del navegador a UTC, usando como extremo
superior la medianoche del día siguiente (exclusiva), incluso en cambios de horario.
Los parámetros de fechas de publicación del API siguen aceptando límites abiertos;
es el frontend el que añade hoy cuando solo se introduce un inicio.

### Vincular un torrent existente con un libro

`POST /api/torrent/downloads/link`

Registra en EPL Sync un torrent ya presente en el cliente, por ejemplo una revisión
anterior a la del catálogo. No envía, renombra ni modifica el torrent y no cambia
la revisión ni los enlaces del libro.

Todos los parámetros van en el cuerpo JSON:

```json
{
  "clientInstanceId": "identificador devuelto por la sincronización",
  "hash": "AAFF84D23D10B2D6386F58ABEA04DC12DB387954",
  "eplId": 30193,
  "revision": 1.7
}
```

Devuelve el registro de descarga, con origen `DISCOVERED` y estado/fecha de
finalización observados en el cliente. El libro debe existir y el torrent debe
seguir presente en el mismo cliente. Se reconocen los hashes alternativos de
torrents híbridos. Repetir la misma vinculación devuelve el registro existente;
una asociación incompatible, un cliente cambiado o un torrent ausente devuelve
409. Los datos inválidos devuelven 400 y un libro inexistente devuelve 404.

En el resultado de sincronización, el torrent queda marcado como «Vinculado».
Los contadores conservan el resultado de aquella comprobación; en la siguiente
sincronización el torrent ya se reconoce a través del historial de descargas.


## 19. Eventos persistentes

El registro es compartido por todas las interfaces y llamadas directas a la API.
Registra catálogo, jobs, envíos individuales de libros, comprobaciones colectivas de portadas y sincronizaciones
aplicadas. Los avisos exclusivos del navegador no se guardan aquí. Las simulaciones
de portadas registran su ejecución, pero no modifican la disponibilidad de portadas.
Las previsualizaciones de importación registran inicio y resultado bajo la acción
`PREVIEW`, con `dryRun=true`, agrupados en una operación. No modifican libros ni
metadatos. Las previsualizaciones de sincronización no generan eventos persistentes.

### Consultar el historial

`GET /api/events?page=0&size=20`

Filtros opcionales: `origin` (`MANUAL`, `SCHEDULED`, `SYSTEM`), `category` (`CATALOG`, `JOB`, `COVERS`, `TORRENT`), `outcome`
(`STARTED`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `PAUSED`, `RETRY_WAIT`, `RESUMED`, `CANCELLED`,
`RECOVERED`), `from` y `before`. Las fechas son instantes ISO con zona: `from` es
inclusivo y `before` exclusivo. Si se proporcionan ambos, `from < before`.
`page >= 0`; este historial admite `size` de 1 a 200 (20 por defecto).

```json
{
  "items": [{
    "id": 42,
    "createdAt": "2026-10-02T10:00:00Z",
    "category": "CATALOG",
    "action": "UPDATE",
    "outcome": "SUCCEEDED",
    "origin": "MANUAL",
    "operationId": "identificador-de-la-ejecucion",
    "details": {"processed": 73591, "created": 10, "updated": 4, "unchanged": 73577, "errors": 0}
  }],
  "total": 1, "page": 0, "size": 20, "cursor": 42
}
```

Orden: más recientes primero. `operationId` relaciona inicio y resultado; en jobs
es su identificador. `origin`: `MANUAL`, `SCHEDULED` o `SYSTEM`. Los valores de
`action` actuales son `UPDATE`, `REPLACE`, `RESET`, `CHECK`, `DOWNLOAD`, `SEND_BOOK` y `SYNC`.
`SEND_BOOK` puede aparecer en eventos históricos. Los nuevos envíos individuales generan trabajos `JOB / DOWNLOAD`. Históricamente incluía `eplId` y,
si tiene éxito, `hash`, `client` y `submissionStatus` (`ACCEPTED` o
`ALREADY_EXISTS`). Un envío completado no significa que la descarga haya terminado.
`details` contiene contadores/resumen, nunca la lista completa de elementos.
Las operaciones síncronas registradas incluyen `X-EPLSync-Operation-Id` en su
respuesta HTTP, también cuando fallan. Un rechazo previo puede no llevarla.

### Consultar ejecuciones agrupadas

`GET /api/events/operations?page=0&size=20`

Devuelve una fila por `operationId`, sin modificar los eventos originales. Admite
los mismos filtros y límites de paginación que el historial. `outcome` filtra el
último estado de la ejecución; `STARTED` incluye las ejecuciones reanudadas o
recuperadas aún sin finalizar. Las fechas filtran el inicio, no la finalización.

Cada elemento contiene:

- `latest`: último evento, con categoría, resultado, origen y detalles.
- `startedAt`: fecha del inicio; `null` si ya no está en el historial.
- `finishedAt`: fecha del último evento si es `SUCCEEDED`, `PARTIAL`, `FAILED`
  o `CANCELLED`; `null` durante ejecución, pausa o espera de reintento.
- `durationMs`: tiempo entre inicio y fin, incluidas pausas; `null` si falta alguno.
- `firstRecordedAt`: fecha del primer evento conservado.
- `events`: eventos conservados de esa ejecución en orden de registro.

Orden descendente por inicio, con el primer ID como desempate. Cuando falta el
inicio por retención/borrado, se usa la fecha del primer evento conservado para
ordenar y filtrar, sin inventar un inicio ni una duración.

La respuesta incluye `items`, `total` (ejecuciones), `page`, `size` y `cursor`.
Para recorrer los mismos datos sin incorporar nuevas entradas, pasar ese cursor
en `snapshot` en las siguientes consultas. Se excluyen los eventos posteriores,
incluidas finalizaciones de ejecuciones ya mostradas. Omitirlo permite actualizar.
Si supera el cursor de una base restaurada se usa el actual. Los borrados y la
retención siguen aplicándose: no es una copia histórica que permita recuperarlos.

### Eventos en tiempo real

`GET /api/events/stream` responde `text/event-stream` (SSE).

- Sin `Last-Event-ID`, envía `ready` con el cursor actual y solo eventos nuevos.
- Con esa cabecera, recupera los eventos posteriores que aún estén conservados.
- `event` incluye el registro y su `id`. `refresh` indica que se debe refrescar
  el historial (incluido un borrado). `reset` reinicia el cursor si procede de
  una base de datos anterior con un contador mayor.
- Comentarios de conexión cada 15 segundos; reconexión automática de EventSource.
- Hasta 32 conexiones por proceso; HTTP 503 cuando no hay plazas. Cada conexión
  se renueva a los 30 minutos. Los proxies deben permitir streaming sin buffering.

Los eventos borrados por retención o mantenimiento no se pueden recuperar.
Las transacciones fallidas no emiten eventos de éxito. El canal transmite eventos
ya confirmados; desconectar un navegador no cancela operaciones del backend.

Los listados aceptan `username` para filtrar por una parte del nombre registrado,
sin distinguir mayúsculas y minúsculas. Este filtro mantiene la restricción de
los eventos de seguridad a administradores. Las acciones puntuales de seguridad
muestran la misma fecha en Inicio y Fin, sin una duración medida.

Cada evento incluye `actor`, con `id`, `username` y `kind` (`USER`, `SYSTEM`
o `UNKNOWN`). El nombre se conserva como instantánea, incluso si la cuenta se
renombra o elimina. Los trabajos en segundo plano mantienen al usuario que los
inició; las operaciones automáticas se identifican como sistema. Los eventos
anteriores sin atribución tienen `kind: "UNKNOWN"`.

### Conservación y borrado

`GET /api/events/retention` devuelve `{ "maxCount": 10000, "maxAgeDays": 365 }`
por defecto. Se configuran desde Ajustes o mediante `eplsync.events.retention.max-count` y
`eplsync.events.retention.max-age-days` en YAML; ambos positivos. Las variables de
entorno de Spring son `EPLSYNC_EVENTS_RETENTION_MAXCOUNT` y
`EPLSYNC_EVENTS_RETENTION_MAXAGEDAYS`. Compose traduce las variables de `.env`
`EPLSYNC_EVENTS_RETENTION_MAX_COUNT` y `EPLSYNC_EVENTS_RETENTION_MAX_AGE_DAYS`
a esos nombres. Limpieza por lotes cada
minuto, aplicando ambos límites. El reinicio del catálogo conserva este historial.

`POST /api/events/delete`, con todos los parámetros en el cuerpo:

```json
{"confirm": true, "from": "2026-10-01T00:00:00Z", "before": "2026-10-02T00:00:00Z"}
```

Devuelve `{ "deleted": 123 }`. Sin fechas elimina todos los eventos existentes al
iniciar el borrado; con solo `before`, los anteriores a ese instante. Los nuevos
creados durante la operación se conservan. Fechas inválidas, rangos invertidos o
`confirm` distinto de `true` devuelven 400. No elimina libros, descargas ni jobs.


`GET /api/events/unread?afterId=42` devuelve `{ "count": 3, "cursor": 48 }`:
cuenta ejecuciones distintas (`operationId`) con eventos conservados posteriores al
identificador indicado. Inicio y fin de la misma ejecución cuentan una sola vez.
Si se leyó el inicio y después llega el fin, esa ejecución vuelve a tener novedades. `afterId`
vale 0 por defecto y debe ser no negativo. Si supera el cursor actual, se cuenta
desde cero para permitir recuperar el indicador tras restaurar una base anterior.
El estado de lectura pertenece al navegador; no modifica el historial compartido.

El listado agrupado GET /api/events/operations admite operationId como filtro
opcional de coincidencia exacta. Devuelve la operación con sus eventos retenidos;
si no existe, devuelve items vacío y total=0.

POST /api/events/unread permite consultar el contador con marcas de lectura
individuales del navegador: cuerpo {"afterId":0,"readIds":[123,456]}.
Es una consulta sin modificaciones en el servidor. readIds contiene los IDs de las
últimas entradas vistas de cada operación (hasta 10000); una entrada posterior de
esa operación vuelve a contar como no leída. GET /api/events/unread?afterId=...
sigue disponible para consultas con un cursor global únicamente.

En GET /api/catalog/directory/authors, los coautores se separan por & y se recortan
los espacios de cada nombre. Se eliminan valores vacíos y duplicados antes de
aplicar búsqueda y paginación. Las comas y anotaciones como (tr) pertenecen al
nombre. CatalogBook.author conserva el texto original del CSV; filtrar por author
sigue buscando coincidencias parciales e incluye libros escritos en colaboración.

El envío individual `POST /api/torrent/books/{eplId}` también admite `batchSize`,
`concurrency` e `interval` en el cuerpo, con los mismos límites y valores
predeterminados de los trabajos múltiples. `options.hash` selecciona el torrent
concreto cuando el libro tiene varios enlaces. La ficha permite elegirlo antes
 de abrir el formulario compartido de opciones de envío.

## Ajustes persistentes del servidor

- `GET /api/settings/{section}` consulta valores efectivos y personalizaciones.
- `PUT /api/settings/{section}` guarda los campos indicados del apartado de forma
  atómica. Los campos omitidos se conservan. No admite simulación.
- `DELETE /api/settings/{section}` restaura los valores de instalación del apartado.

Apartados: `catalog`, `covers`, `torrent`, `events`. Cada campo se identifica por
su clave de configuración sin el prefijo `eplsync.`. Solo se admiten las claves
expuestas por GET para ese apartado; una clave desconocida o un valor inválido
produce `400` sin guardar cambios.

Ejemplo de cuerpo de PUT a `/api/settings/covers`:

```json
{
  "catalog.cover-check.connect-timeout": "3s",
  "catalog.cover-check.request-timeout": "5s",
  "catalog.cover-check.batch-timeout": "6s",
  "catalog.cover-check.concurrency": 8
}
```

GET/PUT/DELETE devuelven `{ "section": "covers", "fields": [...] }`. Cada campo
incluye `key`, `type`, `value`, `overridden` (existe una personalización guardada)
y `configured` (hay una credencial efectiva, solo para secretos). Las duraciones
admiten formatos Spring como `500ms`, `3s`, `24h`. Trackers y etiquetas son arrays
de cadenas. Los valores secretos se devuelven siempre como `""`; omitirlos en PUT
los conserva, enviar `""` los borra y enviar `null` elimina su personalización
para volver al valor de instalación. `null` también restaura un campo no secreto.
Todas las respuestas de ajustes llevan `Cache-Control: no-store`.

Límites: concurrencia de portadas 1–32; timeouts de portadas entre 1ms y 5min,
con conexión ≤ petición ≤ lote; retención ZIP positiva y de hasta 7 días;
lote torrent 1–1000, concurrencia 1–16 e intervalo 0–60s; timeouts torrent
positivos y hasta 5min; límites de retención de eventos enteros positivos.
La integración habilitada requiere credenciales coherentes con su modo de
autenticación. Los ajustes se aplican al servidor, no al navegador que los guarda.

`POST /api/maintenance/reset` conserva cuentas y ajustes por defecto. Para eliminarlos exige `eraseUsersAndSettings=true` y `fullResetConfirmation="BORRAR TODO"`; su resultado incluye
`settingsRecords` con el número de personalizaciones eliminadas.

## Límites de exportación de magnets

GET y POST `/api/catalog/magnets/export` conservan los filtros, la ordenación y
la deduplicación por hash. Devuelven `magnets.txt` como UTF-8, con un enlace por
línea, `Content-Length` y `Cache-Control: no-store`. Un resultado sin enlaces es
un archivo vacío. La respuesta se prepara en disco y se transmite por bloques.

Solo se permite una exportación simultánea por instalación. La preparación y
la transferencia tienen un máximo de dos minutos cada una; el texto y el índice
temporal tienen un máximo de 128 MiB cada uno. Los rechazos incluyen un mensaje
JSON en `details`: HTTP 429 si hay otra exportación, 413 si se supera un tamaño,
408 si caduca la preparación y 409 si cambia la versión del catálogo importado.
La transferencia libera la conexión SQLite del catálogo antes de enviar el archivo.
Los listados y previsualizaciones costosas admiten cuatro solicitudes simultáneas;
el exceso devuelve HTTP 429 con `Retry-After`.

### Clave de configuración inicial

`GET /api/auth/status` devuelve `initialAdminKeyRequired`, un booleano que es
verdadero únicamente mientras la instalación no tiene cuentas y
`EPLSYNC_INITIAL_ADMIN_KEY` está configurada con un valor no vacío. Nunca devuelve
la clave. En ese caso, `POST /api/auth/setup` exige `initialAdminKey` junto a
`username`, `email`, `password` y `passwordConfirmation`. Una clave ausente o
incorrecta devuelve 403; los intentos están sujetos al límite del asistente.
Tras crear la primera cuenta, el asistente devuelve 409 aunque la clave sea correcta.

### Preferencias de la pantalla inicial

`GET /api/auth/home` y `PUT /api/auth/home` consultan y guardan únicamente las
preferencias de la cuenta autenticada. No requieren permisos de administración;
la escritura exige CSRF. No aceptan seleccionar otra cuenta.

El contrato es `{"sections": [...]}`. El orden del array es el orden de Home y
debe contener exactamente una entrada por cada identificador: `header`,
`overview`, `newReleases`, `recentUpdates`, `recentBooks` y `recentEvents`.
Cada entrada contiene `id`, `enabled`, `bookCount` y `eventCount`. `bookCount`
debe estar entre 1 y 100 para las tres secciones de libros y ser `null` para las
demás. `eventCount` indica el número de eventos (1 a 100) de `recentEvents`; es
`null` para las demás secciones. Si falta en preferencias anteriores, se utiliza
10, conservando el orden y la activación guardados.

Por defecto todas las secciones están activadas, con 10 libros por sección y 10 eventos. Las preferencias se guardan en SQLite junto a la cuenta
y se conservan al cerrar sesión, cambiar de navegador o reiniciar la aplicación.
Eliminar la cuenta o reiniciar los datos incluyendo usuarios elimina también sus
preferencias. Activar una sección no concede permisos sobre su contenido.

### Historial completo de un libro

`GET /api/catalog/books/{eplId}/history` requiere `CATALOG_READ` y
`BOOK_HISTORY_READ`. Devuelve registros paginados de ese libro, con `id`, `hash`,
`revision`, `status`, `client`, `clientInstanceId`, `origin`, `lastCheckedAt`,
`completedAt`, `lastError` y el indicador `completed`.

Admite `page` (0 por defecto), `size` (20 por defecto, con el límite general de
paginación) y `sort=campo,asc|desc`. Los campos permitidos son `hash`, `revision`,
`status`, `client`, `lastCheckedAt`, `completedAt` y `lastError`. Por defecto se
ordena por `revision,desc`, de mayor a menor revisión, con fecha de creación e identificador como
desempate estable. La ordenación se aplica antes de paginar; no admite filtros.

### Ordenación del listado de trabajos

`GET /api/torrent/jobs` admite `sort=campo,asc|desc`, además de `page`, `size` y
`status`. Puede repetirse para indicar hasta 8 criterios por prioridad; por ejemplo,
`sort=status,asc&sort=progress,desc`. Los campos permitidos son `jobId`, `type`, `status`, `progress`, `selectedBooks`,
`accepted`, `failed` y `createdAt`. El orden predeterminado sigue siendo
`createdAt,desc`.

`progress` compara la fracción `processedItems / selectedItems`: considera
procesados los elementos aceptados, ya existentes, omitidos o fallidos. Un
trabajo sin elementos tiene progreso 0. Los contadores y porcentajes se ordenan
en SQLite antes de paginar, sin cargar todo el listado en memoria. Los empates
se resuelven por fecha de creación e identificador.

### Preferencias de búsqueda de actualizaciones

`GET/PUT /api/settings/downloads/updates` requiere `CATALOG_READ` y `DOWNLOADS_READ`.
El cuerpo es `{"states":["DOWNLOADED","NOT_FOUND"]}`: al menos un estado de
 descarga válido, sin duplicados. La lectura requiere ambos permisos; la escritura requiere `SETTINGS_MANAGE`.
Se guarda como JSON compartido en `revision_update_settings`, para toda la aplicación.
Por defecto se incluyen todos los estados.
Estos valores constituyen la configuración base para las búsquedas de revisiones;
la búsqueda y el envío de actualizaciones se implementarán por separado.

Los apartados de portadas se encuentran en `/settings/catalog`;
`/settings/covers` redirige allí para conservar enlaces antiguos.

### Buscar actualizaciones de revisión

`POST /api/torrent/revision-updates/search` requiere `CATALOG_READ` y
`DOWNLOADS_READ`, y protección CSRF. Cuerpo: `{"states":["DOWNLOADED","NOT_FOUND"]}`.
Parámetros: `page=0`, `size=20` (máximo 1000), `sort=title,asc` (repetible, hasta 8 criterios por prioridad) y
`synchronize=false` y `status` opcional (estado registrado). El filtro `status`
se aplica a las filas resultantes antes de paginar; no cambia los estados que
determinan la revisión de referencia. `synchronize=true` requiere además `TORRENT_SYNC`:
sincroniza primero con el cliente; cualquier fallo aborta la búsqueda.
La paginación y ordenación posteriores no repiten la sincronización.

Devuelve una página con `eplId`, `title`, `registeredRevision`,
`availableRevision`, `status`, `coverUrl` y `coverAvailable`. Ordenación por EPL ID, título, revisiones y estado,
salvo que el título se ordena como `title`, con dirección `asc` o `desc`.

Los estados seleccionados determinan la revisión registrada más alta que se
compara con el catálogo. Solo cuentan registros con evidencia de envío aceptado,
observación en el cliente o finalización; un intento rechazado no cuenta.
Si cualquier registro del historial acredita una revisión igual o superior a la
 del catálogo, el libro se excluye, independientemente de su estado o del destino.
Los trabajos activos con una revisión igual o superior pendiente de envío también
lo excluyen. Se devuelve una sola fila por libro, ordenada antes de paginar.

`/downloads/updates` carga los valores de Ajustes > Descargas, permite modificarlos
para esa búsqueda y enviar los libros seleccionados mediante el asistente habitual.
No modifica la configuración guardada ni elimina versiones anteriores.

### Envío seleccionado y limpieza automática de revisiones

`POST /api/torrent/revision-updates/send` requiere `CATALOG_READ`,
`DOWNLOADS_READ` y `TORRENT_SEND`. Cuerpo: `ids` (1–10000 IDs únicos),
`states` (estados de la búsqueda), opciones habituales de envío y
`previousVersions`: `keep` (predeterminado), `removeTorrent` o
`removeTorrentAndFiles`. Las políticas de eliminación requieren `TORRENT_CLEANUP`;
la última también requiere `TORRENT_FILES_DELETE` y `confirmFiles=true`.

La selección se comprueba de nuevo antes de crear el trabajo. Solo se planifica
la limpieza de versiones anteriores del destino actual. Los trabajos creados
por este endpoint guardan `automaticCleanup=true` si se solicita eliminación;
los planes anteriores creados mediante la API conservan la ejecución manual.

La limpieza utiliza solicitudes independientes por revisión: cada registro conserva
su cliente, política, usuario, fecha de solicitud y hashes de sustitución. El trabajo
es una referencia de origen; el ejecutor no necesita recorrer sus elementos.
El temporizador provisional selecciona hasta 100 solicitudes por ciclo, cada 30
segundos. Prioriza las más antiguas no comprobadas y rota las ya comprobadas,
incluidas las bloqueadas. Comparte una instantánea inicial del cliente y consulta
de nuevo si se solicitaron borrados, para confirmar su ausencia.

Los permisos se comprueban al crear la solicitud. La ejecución automática conserva
esa autorización para el destino, los hashes y la política registrados, aunque después
se retiren permisos, se desactive o se elimine la cuenta. La identidad del solicitante
queda guardada para los eventos; no se necesita consultar su cuenta al ejecutar.
Las nuevas solicitudes y las acciones manuales requieren los permisos actuales.
Cancelar un trabajo cancela sus
limpiezas todavía no solicitadas; las `REQUESTED` conservan la confirmación pendiente.
Los borrados inciertos nunca se repiten automáticamente. Los registros `REMOVED`,
`KEPT` y `CANCELLED` no vuelven a seleccionarse. La ausencia confirmada resuelve
las solicitudes del mismo torrent y destino y conserva su historial como `NOT_FOUND`.

Configuración provisional, sin pestaña de tareas programadas:
`EPLSYNC_TORRENT_CLEANUP_ENABLED` (true), `EPLSYNC_TORRENT_CLEANUP_INTERVAL` (30s)
y `EPLSYNC_TORRENT_CLEANUP_BATCH_SIZE` (100, entre 1 y 1000). Desactivar el temporizador
no impide operaciones manuales. La programación está separada de `CleanupQueue`,
que podrá invocarse desde el futuro planificador.

La sincronización manual aplicada comprueba también las limpiezas pendientes
del destino actual, reutilizando su respuesta y respetando `TORRENT_CLEANUP` y
`TORRENT_FILES_DELETE`. La previsualización y una consulta fallida no ejecutan borrados.
Las acciones manuales bloqueadas del historial no se convierten en borrados diferidos;
los intentos `REQUESTED` solo se revisan para confirmar su ausencia.

### Acciones sobre registros del historial

`POST /api/torrent/downloads/refresh-selected` y
`POST /api/torrent/downloads/remove-selected` reciben JSON:

```json
{"eplId":123,"ids":["id-del-registro"],"deleteFiles":false,"confirmFiles":false}
```

Se admiten entre 1 y 1000 IDs únicos del mismo libro y destino actual. Ambos requieren
`BOOK_HISTORY_READ`; actualizar exige `TORRENT_SYNC`, eliminar `TORRENT_CLEANUP`,
y borrar archivos también `TORRENT_FILES_DELETE` y `confirmFiles=true`.
Actualizar consulta únicamente los hashes seleccionados en qBittorrent y guarda
los estados; nunca solicita borrados. Eliminar es inmediato, conserva el historial
y mantiene las protecciones de torrents compartidos, objetivos pendientes y rutas.
Su respuesta `items` contiene `id`, `status`, `cleanupState` y `message`, con resultado
por registro; una operación puede terminar parcialmente. Los resultados inciertos
quedan `REQUESTED`. Un reintento explícito admite `remove-selected?retryUnconfirmed=true`;
no se habilita implícitamente desde el formulario.

Los trabajos exponen `type` (`DOWNLOAD` o `UPDATE`), `previousVersions` y un resumen
`cleanup` con `waiting`, `blocked`, `requested`, `removed` y `cancelled`.

La vista conserva la última búsqueda, filtro, ordenación, tamaño y página en
memoria durante la sesión. Volver a ella no sincroniza ni repite consultas
automáticamente; muestra la fecha de la última búsqueda. El título reutiliza
la portada del catálogo y su mismo componente visual.

### Controles de tablas de Descargas

Estado, Actualizaciones, Trabajos y los elementos de un trabajo comparten los
controles «Ordenar» y «Columnas» con el estilo de Catálogo. «Ordenar» permite
añadir, quitar y reordenar criterios; Mayús + clic en una cabecera añade un
criterio. La ordenación se aplica en el servidor antes de paginar.

«Columnas» permite mostrar u ocultar campos, cambiar su orden mediante arrastre
o flechas y restaurar la distribución predeterminada. Los anchos, el orden y
la visibilidad se guardan por tabla en este navegador. Siempre queda al menos
una columna visible; la columna de selección de Actualizaciones permanece fija.
«Enviar actualizaciones» aparece junto al contador cuando hay libros seleccionados.

Los mismos controles están disponibles en Eventos, Historial asociado y las
listas de libros y torrents ignorados del informe de sincronización. En este
informe se ordena el conjunto filtrado en memoria antes de paginar, sin repetir
la sincronización. Libros separa EPL ID, título con portada y hash. Los campos
Resumen de Eventos y Acciones de torrents ignorados no son criterios de ordenación.

`GET /api/events/operations` acepta `sort` repetido (máximo 8 criterios) con
campos `startedAt`, `finishedAt`, `duration`, `category`, `event`, `outcome`,
`origin` y `user`. Predeterminado: `startedAt,desc`; conserva el snapshot y añade
el identificador inicial como desempate. Los filtros y la visibilidad de los
eventos de seguridad se mantienen. El historial de un libro también acepta
`sort` repetido; mantiene `revision,desc` por defecto.

Cuando la sincronización incluye detalles, cada libro incorpora `coverUrl` y
`coverAvailable` del catálogo. No se descargan ni almacenan las imágenes;
se presentan mediante el mismo componente de portada que las demás tablas.

### Momento del borrado de versiones anteriores

El envío de actualizaciones admite `cleanupTiming`: `afterDownload`
(predeterminado) o `immediate`. En el asistente, el apartado «Versiones anteriores»
contiene «Acción» y, cuando se solicita eliminar, «Momento del borrado».
El informe del trabajo muestra ambas elecciones.

`immediate` intenta la limpieza después de que el cliente haya aceptado todos
los hashes seleccionados de la nueva revisión de cada libro, incluidos los que
ya existen en el cliente. No espera a que termine la descarga. Si hay un envío
pendiente o fallido, no se eliminan sus versiones anteriores. La aceptación
queda persistida en la solicitud de limpieza; una vez aceptada, no depende de
conservar el trabajo ni sus elementos.

Los intentos se ejecutan en segundo plano después de confirmar la transacción,
sin esperar al siguiente ciclo periódico. Se conserva la cola de limpieza,
la autorización concedida al crear la solicitud, la protección de torrents y rutas compartidos y
la comprobación posterior al borrado. Los intentos y los nuevos bloqueos generan
eventos; un bloqueo idéntico no produce eventos repetidos en cada ciclo.
Los errores o bloqueos permanecen disponibles para las comprobaciones posteriores.
La opción de conservar ignora el momento del borrado. Las instalaciones y
solicitudes anteriores mantienen el comportamiento de esperar a la descarga.

Aceptar un envío no garantiza que la nueva revisión llegue a descargarse:
con `immediate` puede haber un intervalo sin ninguna revisión descargada.

### Eliminar registros del historial de descargas

`POST /api/torrent/downloads/delete-history` exige `BOOK_HISTORY_READ` y
`DOWNLOADS_DELETE`. Este último se concede por defecto a ADMIN y puede asignarse
individualmente a USER. Cuerpo JSON:

```json
{"eplId": 123, "ids": ["id-del-registro"], "confirm": true}
```

Admite entre 1 y 1000 IDs distintos del mismo libro. Valida la selección completa
antes de eliminarla en una transacción; también permite registros de destinos
anteriores y no requiere tener habilitado el cliente torrent. `confirm=true` es
obligatorio para borrar. La respuesta contiene `deleted` y `pendingCleanup`.

Con `?preview=true` valida sin borrar ni exigir confirmación y devuelve el número
actual de solicitudes de limpieza pendientes asociadas. La interfaz utiliza este
resultado para advertir antes del borrado. El borrado genera el evento
`DELETE_DOWNLOAD_HISTORY`, con el usuario responsable y el número de registros.

Solo elimina registros de `torrent_downloads`: no contacta con qBittorrent, no
elimina torrents ni archivos y conserva trabajos, eventos y solicitudes de limpieza.
Las limpiezas siguen usando sus hashes y condiciones persistidos aunque desaparezca
el registro original. Eliminarlo no cancela una limpieza.

La sincronización puede recrear como `DISCOVERED` los hashes que sigan en el cliente
y en el catálogo actual; no recupera los datos históricos del envío. Los hashes
antiguos ausentes del catálogo no se redescubren automáticamente. Borrar todas las
referencias de un libro puede hacer que deje de aparecer en futuras búsquedas de
actualizaciones hasta que se vuelva a registrar una revisión.

El botón único «Eliminar» del historial abre un popup con un checkbox para eliminar
el registro de EPL Sync y un selector para conservar el cliente, eliminar solo el
torrent o eliminar torrent y archivos. El borrado de archivos requiere confirmación
adicional. Las opciones se ofrecen según los permisos disponibles.

`POST /api/torrent/downloads/remove-records` combina ambas acciones:

```json
{"eplId":123,"ids":["id-del-registro"],"deleteHistory":true,"clientAction":"files","confirm":true,"confirmFiles":true}
```

`clientAction` admite `keep`, `torrent` o `files`. Exige `BOOK_HISTORY_READ` y, según
las opciones, `DOWNLOADS_DELETE`, `TORRENT_CLEANUP` y `TORRENT_FILES_DELETE`.
Todos los permisos y la selección se validan antes de actuar. Debe elegirse al menos
una eliminación y confirmarla. La respuesta contiene `items`, con `id`, `status`,
`cleanupState`, `message` y `historyDeleted` por registro.

Al combinar ambas acciones, primero intenta el borrado en el cliente con las
comprobaciones de seguridad habituales y confirma su ausencia. Solo elimina del
historial los registros con `cleanupState=REMOVED`; conserva los bloqueados o con
borrado sin confirmar. El borrado confirmado resuelve las solicitudes de limpieza
asociadas al mismo torrent y destino. Los eventos del cliente y del historial se
registran por separado. Una operación puede terminar con resultados distintos
para cada registro seleccionado.
