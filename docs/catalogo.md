# Catálogo y magnets

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Importación del catálogo

Los tres modos descargan el ZIP oficial de ePubLibre. Admiten el parámetro
opcional `url` para indicar otro ZIP mediante HTTP o HTTPS.

El CSV admite la columna opcional `Portada`, con la URL de la imagen. Se guarda
en `catalog_books.cover_url` y se expone como `coverUrl` en el detalle y los
listados de libros. Si falta la columna o el valor está vacío, se guarda `null`,
también al actualizar un libro que antes tenía portada. Los cambios de portada
se incluyen en la previsualización como `coverUrl`.

El importador corrige el defecto conocido de una comilla doble sobrante después
de una URL HTTP/HTTPS entrecomillada en la última columna `Portada`. Conserva
los campos vacíos (`""`), las comillas escapadas y las sinopsis multilínea.
La corrección se aplica tanto a la importación como a la previsualización.

La nueva columna nullable se añade al arrancar mediante la actualización de
esquema existente; no hace falta reiniciar ni vaciar la base de datos.
El frontend muestra la URL guardada y, solo si no existe, usa
`https://images.epublibre.org/libros/{eplId}.jpg`. Las imágenes se cargan desde
el navegador; si no se pueden cargar, se conserva el icono de libro. En el detalle,
la portada enlaza a la imagen completa en una nueva pestaña.

## Comprobar disponibilidad de portadas

`coverAvailable` (`cover_available` en SQLite) es nullable: `true` indica una
respuesta HTTP 200 de tipo imagen, `false` un HTTP 404/410, y `null` que aún no
se ha comprobado. No se guarda fecha de comprobación. La URL original se conserva.
El frontend utiliza ePubLibre cuando el estado es `false` o falta la URL; no
realiza peticiones de validación durante la navegación. Un error temporal de una
comprobación no modifica el estado anterior.

```sh
# Prueba de un libro, incluso si ya tenía un estado guardado: no escribe nada.
curl -s -X POST 'http://localhost:8088/api/catalog/covers/check' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"eplId":2725,"onlyUnchecked":false}'

# Comprueba de nuevo y guarda los resultados concluyentes de ese libro.
curl -s -X POST 'http://localhost:8088/api/catalog/covers/check' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"eplId":2725,"onlyUnchecked":false}'

# Primer lote de URLs sin comprobar.
curl -s -X POST 'http://localhost:8088/api/catalog/covers/check' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"size":20}'
```

El POST exige `dryRun` booleano en el cuerpo JSON y acepta estas opciones:

| Parámetro | Predeterminado | Comportamiento |
| --- | --- | --- |
| `eplId` | Sin filtro | Restringe la comprobación a ese libro. |
| `size` | Sin límite | Si se indica, limita el total de libros comprobados; entero positivo. |
| `afterId` | `0` | Selecciona IDs mayores, en orden ascendente. |
| `onlyUnchecked` | `true` | Selecciona solo estados `null`; `false` permite revisar cualquiera. |

El body admite además `coverAvailable=true` o `coverAvailable=false` para mostrar
solo resultados disponibles o no encontrados, respectivamente. Si se omite,
se muestran todos, incluidos los inconcluyentes. Este filtro se aplica al resultado
**recién comprobado**, no al estado almacenado en BD, y solo filtra `items`:
los contadores y el cursor siguen describiendo todos los libros comprobados.

```sh
# Comprobar TODO el catálogo con URL, incluso estados ya guardados, sin escribir.
# Mostrar únicamente los resultados no encontrados.
curl -s -X POST 'http://localhost:8088/api/catalog/covers/check' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"coverAvailable":false,"onlyUnchecked":false}'
```

**Sin `size`, una llamada recorre automáticamente todos los libros seleccionados**,
leyendo la base de datos en lotes internos pequeños. No hay límite total de libros.
El POST con `{"dryRun":true}` comprueba todos los que tengan URL y estado `null`; para
incluir los ya comprobados, usar `onlyUnchecked=false`. Al completar el recorrido,
`hasMore=false` y `nextAfterId=null`.

Si se especifica `size`, limita libros comprobados, no coincidencias del filtro:
puede devolver `items: []` y `hasMore: true`. En ese caso se puede continuar con
`afterId=nextAfterId` y los mismos filtros. El filtro de visualización es exclusivo
de la respuesta y no restringe las escrituras cuando `dryRun=false`.

La petición es síncrona: devuelve el resumen y los detalles al terminar el recorrido.
Con decenas de miles de URLs puede tardar mucho; el cliente o proxy debe permitir
mantener la conexión abierta durante ese tiempo. No es un trabajo en segundo plano.
POST guarda los resultados en una transacción después de completar las comprobaciones.
Los libros sin URL se omiten. Para repetir exactamente la selección de una simulación hay que usar sus mismos
parámetros en el POST, no su cursor de continuación. POST vuelve a consultar las
URLs: no aplica una instantánea previamente guardada por la simulación.

La respuesta incluye `dryRun`, `checked`, `available`, `unavailable`,
`inconclusive`, `wouldChange`, `updated`, `hasMore`, `nextAfterId` e `items`.
Cada resultado contiene `eplId`, `coverUrl`, `previousAvailable`, `available`,
`httpStatus`, `reason`, `wouldChange` y `updated`. Con `dryRun=true`, `updated` siempre es cero
y los elementos tienen `updated=false`. Un resultado inconcluyente tiene
`available=null`; no significa que se vaya a borrar un estado previo.

Se hacen GET externos (sin descargar deliberadamente el cuerpo completo), hasta
cuatro en paralelo por defecto, con un máximo de tres redirecciones y presupuesto
predeterminado de tres segundos por URL. Cada grupo tiene además un límite
predeterminado de cuatro segundos.
Las URLs repetidas se consultan una sola vez durante todo el recorrido de la petición. Solo puede ejecutarse una
comprobación por instancia: otra petición concurrente recibe HTTP 409. Las consultas
normales del catálogo continúan disponibles y no se mantiene una transacción de
base de datos durante las peticiones externas.

En el log del backend se registra a nivel INFO el inicio (modo prueba o escritura,
filtros y total seleccionado), el progreso aproximadamente cada 10 % y un resumen
final con disponibles, no encontrados, inconcluyentes, cambios propuestos/aplicados,
URLs distintas consultadas y duración. El porcentaje cuenta libros, no URLs únicas,
y usa el total al iniciar, respetando `size` y los filtros de selección. En catálogos
que cambien durante la ejecución ese total es orientativo. En selecciones muy
pequeñas los porcentajes pueden saltar más del 10 %; con cero candidatos solo hay
inicio y resumen. El 100 % indica el fin de las comprobaciones; el resumen final
del POST se escribe después de guardar. Las interrupciones y fallos también se registran.

Los tiempos y la concurrencia se configuran en `backend/src/main/resources/application.yaml`:

```yaml
eplsync:
  catalog:
    cover-check:
      connect-timeout: 3s
      request-timeout: 3s
      batch-timeout: 4s
      concurrency: 4
```

- `connect-timeout`: tiempo máximo para establecer una conexión nueva.
- `request-timeout`: presupuesto por URL, compartido por la validación del destino
  y las peticiones que se hagan al seguir redirecciones. No se reinicia por redirección.
- `batch-timeout`: límite externo del grupo paralelo, que cancela tareas pendientes,
  incluso cuando todavía no han recibido cabeceras. El siguiente grupo espera a que
  termine o venza el actual.
- `concurrency`: número de URLs simultáneas, de 1 a 32. Aumentarlo puede reducir
  duración, pero también aumentar los rechazos o límites del servidor remoto.

Se admiten duraciones como `500ms`, `3s` o `1m`, entre 1 ms y 5 minutos, con
`connect-timeout <= request-timeout <= batch-timeout`. Las configuraciones inválidas
impiden el arranque con un error de validación. El log de inicio muestra los valores
efectivos. Estos límites no son esperas obligatorias: una respuesta rápida termina
antes. No hay reintentos automáticos ni aumentos progresivos del timeout.

Para ampliar el presupuesto por URL a 10 segundos, habría que ampliar también
`batch-timeout`, por ejemplo a 11 segundos; cambiar solo `request-timeout` no es
válido si el límite del grupo sigue en 4 segundos. Los valores predeterminados no
se han aumentado. Un cambio en el YAML empaquetado requiere reconstruir la imagen
y reiniciar; también se pueden proporcionar estas variables en el entorno del contenedor:
`EPLSYNC_CATALOG_COVERCHECK_CONNECTTIMEOUT`, `EPLSYNC_CATALOG_COVERCHECK_REQUESTTIMEOUT`,
`EPLSYNC_CATALOG_COVERCHECK_BATCHTIMEOUT` y `EPLSYNC_CATALOG_COVERCHECK_CONCURRENCY`.

### Gestión de portadas desde la interfaz

En **Ajustes → Portadas** se puede iniciar la comprobación de todo el catálogo.
«Incluir portadas ya revisadas» está activado por defecto (`onlyUnchecked=false`).
Al desactivarlo, solo se comprueban portadas con estado `null` (`onlyUnchecked=true`). El formulario parte de los valores del
servidor y permite cambiar los tres tiempos (en segundos) y la concurrencia
**solo para esa ejecución**. Por defecto guarda los resultados concluyentes;
«Solo comprobar, sin guardar cambios» permite hacer una prueba.

La interfaz usa una tarea en segundo plano: no mantiene abierta una petición HTTP
durante todo el catálogo. Muestra progreso y resumen, y permite salir de la vista
o cerrar el navegador. Se admite una tarea a la vez y se conserva la última tarea
en memoria hasta reiniciar el servidor; no se reanuda automáticamente tras un
reinicio. Mientras está activa, el reinicio completo de la BD devuelve conflicto.
Al terminar se actualizan las consultas del catálogo y del libro abierto.

En el encabezado del detalle del libro, **Reparar portada** aparece como una acción
de texto junto a los datos de la portada, separada de los botones de descarga. Comprueba ese libro y guarda el resultado
concluyente. Si no se encuentra, la portada alternativa aparece sin recargar la
página. Si la respuesta es positiva o inconcluyente, un diálogo explica el motivo
y ofrece «Cerrar» o **Usar portada alternativa**. Esta última acción guarda
`coverAvailable=false` sin borrar la URL. Se rechaza si la URL cambió desde la
comprobación, para no modificar otra portada accidentalmente. Una comprobación
posterior que encuentre la URL disponible puede volver a establecer `true`.
Los textos de estas acciones no identifican el servidor de la portada alternativa.

Endpoints utilizados por la interfaz:

| Método | Ruta | Función |
| --- | --- | --- |
| GET | `/api/catalog/covers/config` | Valores predeterminados efectivos. |
| POST | `/api/catalog/covers/task` | Inicia tarea completa; devuelve HTTP 202 con ID y estado. |
| GET | `/api/catalog/covers/task` | Devuelve `{ "task": null }` o la última tarea con progreso y resumen. |
| POST | `/api/catalog/covers/{eplId}/alternative` | Activa explícitamente la alternativa conservando la URL. |

Ejemplo de cuerpo para iniciar una tarea (los tiempos de la API se expresan en milisegundos):

```json
{
  "dryRun": false,
  "onlyUnchecked": false,
  "options": {
    "connectTimeoutMs": 3000,
    "requestTimeoutMs": 3000,
    "batchTimeoutMs": 4000,
    "concurrency": 4
  }
}
```

Si se omite `options`, usa los valores del servidor; `dryRun` es obligatorio y debe ser un booleano JSON. `onlyUnchecked` también es `false` por defecto en este endpoint
de tareas (el endpoint síncrono `/check` conserva su valor predeterminado `true`).
Siempre recorre todo el catálogo con URL que cumpla ese filtro, sin límite de libros.
La respuesta de la tarea incluye `onlyUnchecked` para conservar su alcance al navegar. Devuelve HTTP 409 si hay otra comprobación en curso y HTTP 400
para parámetros inválidos. Los estados de tarea son `RUNNING`, `COMPLETED` y
`FAILED`; el resumen omite detalles individuales para mantener pequeña la respuesta.
El endpoint síncrono POST `/check` exige igualmente `dryRun` en JSON; su antiguo GET ya no se admite.

La activación manual exige el cuerpo `{"expectedCoverUrl":"URL comprobada"}`.
Devuelve `{"eplId":123,"coverAvailable":false}`; HTTP 404 si no existe el libro,
409 si cambió la URL y 400 si falta una URL válida para comparar.

Timeouts, errores de red, HTTP 403/429/5xx, respuestas que no declaran un tipo imagen
y redirecciones inválidas son inconcluyentes. Solo HTTP/HTTPS sin credenciales y
destinos resueltos a direcciones públicas se admiten, también tras redirecciones.
Una respuesta HTTP 200 con una imagen de error del proveedor no se puede distinguir
de una portada mediante esta comprobación de cabeceras.

La importación conserva el estado cuando la URL no cambia, aunque cambien otros
datos del libro. Si cambia o desaparece la URL, el estado vuelve a `null`. Un
reemplazo completo del catálogo crea registros nuevos sin comprobar. Si otro proceso
cambia la URL o su estado mientras se comprueba, no se sobrescriben esos cambios:
el resultado indica `CONCURRENT_CHANGE`.

## Reemplazar el catálogo

```sh
curl -i -X POST http://localhost:8088/api/catalog/import/reset
```

Borra los registros de `catalog_books` e importa el catálogo desde cero.
No elimina el archivo SQLite ni otras tablas. Los libros importados reciben una
nueva `insertDate` y `lastModifiedDate` queda en `null`.

## Reiniciar toda la base de datos

`POST /api/maintenance/reset` requiere un cuerpo JSON `{"confirm":true}`.
Vacía las ocho tablas de datos: libros, metadatos del catálogo, descargas,
trabajos, elementos de trabajos, planes de actualización, registros de limpieza y eventos.
**No descarga ni importa el CSV**. El frontend utiliza este endpoint; no utiliza
`/api/catalog/import/reset`, que continúa reemplazando solo el catálogo desde el CSV.

Conserva el archivo SQLite y su esquema, la configuración, los logs y las preferencias
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

## Actualizar el catálogo

```sh
curl -s -X POST 'http://localhost:8088/api/catalog/import/run' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"source":"URL"}'
```

Identifica cada libro por `eplId`. Inserta los nuevos y actualiza los existentes
solo si cambia algún dato del CSV. Conserva `insertDate` y establece
`lastModifiedDate` al modificar un libro. Los libros ausentes del CSV se conservan.

Ejemplo de respuesta de `/reset` y `/update`:

```json
{
  "success": true,
  "message": "Importación completada",
  "recordsProcessed": 100,
  "errors": 0,
  "recordsUpdated": 20,
  "recordsCreated": 10,
  "recordsUnchanged": 70
}
```

`recordsProcessed` es la suma de nuevos, modificados y sin cambios. `errors`
cuenta las filas omitidas por errores de conversión o campos obligatorios ausentes.
Los contadores se refieren a filas procesadas del CSV. Los `eplId` repetidos
se omiten y cuentan como errores, tanto en la importación como en la previsualización. Un error de persistencia o de lectura que aborte la importación revierte
la transacción completa, incluido el borrado del modo reemplazo.

## Previsualizar una actualización

```sh
curl -s -X POST 'http://localhost:8088/api/catalog/import/run' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"source":"URL"}'
```

Descarga y compara el CSV sin insertar, actualizar ni borrar libros. Por defecto
solo devuelve `success`, `message`, `recordsProcessed`, `errors`, `recordsUpdated`,
`recordsCreated` y `recordsUnchanged`, sin listas de libros.

Para consultar los libros completos desde un frontend, solicita explícitamente el detalle:

```sh
curl -s -X POST 'http://localhost:8088/api/catalog/import/run' \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":true,"includeDetails":true,"page":0,"size":50,"source":"URL"}'
```

Con `includeDetails=true` devuelve:

- `summary`: los mismos contadores de importación, calculados sobre todo el CSV.
- `page` y `size`: página desde cero y tamaño (predeterminado 50, sin máximo de aplicación).
- `createdBooks`: libros nuevos completos de la página solicitada.
- `updatedBooks`: cambios de la página, con `before`, `after` y `changedFields`.

Las dos listas se paginan **por separado**, en orden de aparición en el CSV.
Por ejemplo, `page=1&size=50` devuelve los elementos 51–100 de cada lista.
Los totales de `summary.recordsCreated` y `summary.recordsUpdated` permiten
calcular las páginas; una página fuera del rango devuelve una lista vacía.

Los libros nuevos muestran fechas locales nulas. En `after`, las fechas locales
conservan sus valores actuales: las nuevas fechas se asignan al guardar realmente.
Los nombres de `changedFields` son los campos Java/JSON, como `title` o `revision`.

Cada petición vuelve a descargar y comparar el catálogo, también al cambiar de
página. No se guarda una instantánea ni un identificador para aplicar la vista:
si el CSV remoto o la BD cambian, una petición posterior puede devolver resultados
distintos. `/update` realiza una nueva descarga y comparación.

## Fechas de seguimiento local

El campo Java/JSON `insertDate` corresponde a la columna `insert_date` y conserva
el instante de la primera inserción en la BD. `lastModifiedDate` mantiene su nombre
y solo cambia cuando se modifica el libro; inicialmente es `null`. Ambos usan
`Instant` y se devuelven como fecha y hora UTC, por ejemplo
`2026-09-29T07:16:18.123Z`. `publicationDate` sigue siendo una fecha sin hora.

Se parte de una instalación nueva: Hibernate crea directamente la columna
`insert_date`. No se incluye una migración desde esquemas anteriores.

## Enlaces magnet

La API genera enlaces BitTorrent v1 a partir de `CatalogBook.links`, sin descargar
metadatos ni conectar con qBittorrent. Conserva los endpoints de libros existentes.

| Petición GET | Respuesta |
| --- | --- |
| `/api/catalog/books/{eplId}/magnets` | Array JSON de magnets del libro; `[]` si no tiene hashes válidos y `404` si no existe |
| `/api/catalog/magnets` | Array JSON de magnets de los libros filtrados |
| `/api/catalog/magnets?page=0&size=20` | `{ "items": [...], "meta": {...} }`, con la misma estructura de metadatos que la búsqueda de libros |
| `/api/catalog/magnets/export` | Archivo `magnets.txt`, `text/plain;charset=UTF-8`, con un magnet por línea |

La búsqueda y la exportación admiten los mismos filtros del catálogo (`author`,
`title`, `language`, fechas, estados, etc.) y `sort=title,asc`, por ejemplo.
El orden predeterminado es `eplId,asc`; también se usa `eplId` para desempatar.
La exportación devuelve todos los resultados filtrados, sin paginación.

```sh
curl 'http://localhost:8088/api/catalog/books/85202/magnets'
curl 'http://localhost:8088/api/catalog/magnets?author=Wells&page=0&size=20'
curl 'http://localhost:8088/api/catalog/magnets/export?author=Wells' -o magnets.txt
```

Se genera un magnet por hash distinto. Se aceptan hashes hexadecimales de 40
caracteres y Base32 de 32 caracteres; estos últimos se convierten a hexadecimal.
Los hashes del catálogo se separan por comas; también se admiten espacios,
saltos de línea y punto y coma. Los valores inválidos se omiten. La deduplicación
se hace por hash, incluso entre libros: se conserva el nombre del primer libro
según el orden solicitado.

Si se proporciona `page` o `size`, se pagina **después** de expandir y deduplicar
los hashes. Los totales cuentan magnets, no libros. Por defecto `page=0` y
`size=20`; `size` admite enteros positivos, sin máximo de aplicación. Una página fuera del rango devuelve
`items: []`; parámetros de paginación inválidos devuelven `400`.
Para calcular totales exactos, esta primera versión procesa en memoria todos los
resultados filtrados, cargando únicamente `eplId`, `title` y `links` de la BD.

El nombre sugerido es `EPL_{eplId}_{title}`, codificado en UTF-8 en `dn`.
`dn` no garantiza el nombre permanente en qBittorrent ni renombra los archivos:
el cliente puede sustituirlo al recibir los metadatos. Para fijar el nombre
mostrado se necesita la operación de renombrado del cliente.
La URL HTTP de estos endpoints no es un archivo `.torrent`; se deben utilizar
los enlaces `magnet:` devueltos (por ejemplo, copiando el texto exportado en el
cuadro de añadir enlaces del cliente).

## Trackers y Docker

En `backend/src/main/resources/application.yaml` se configura la lista global:

```yaml
eplsync:
  torrent:
    trackers:
      - "udp://tracker.opentrackr.org:1337/announce"
```

`EPLSYNC_TORRENT_TRACKERS` sustituye **toda** la lista con URLs separadas por comas.
Cada URL genera un parámetro `tr`. Se eliminan duplicados y espacios exteriores.
Se admiten HTTP, HTTPS y UDP (este último requiere puerto). Una URL inválida impide
el arranque para detectar errores de configuración. Las direcciones no se prueban
contra la red: su configuración no garantiza que el tracker esté disponible.

Ejemplo de variable a añadir al bloque `environment` del servicio en `docker-compose.yml`:

```yaml
services:
  eplsync:
    # Conservar el resto de la configuración del servicio.
    environment:
      EPLSYNC_TORRENT_TRACKERS: "${EPLSYNC_TORRENT_TRACKERS-udp://tracker.opentrackr.org:1337/announce}"
```

En el `.env` situado junto a `docker-compose.yml`:

```dotenv
EPLSYNC_TORRENT_TRACKERS=udp://tracker.opentrackr.org:1337/announce,udp://www.torrent.eu.org:451/announce
```

Alternativamente, usar `env_file: .env` en el servicio en lugar del bloque
`environment`. Compose lee `.env` para interpolación; por sí solo no introduce
todas sus variables en el contenedor. Spring tampoco carga automáticamente un
archivo `.env` al ejecutarse fuera de Docker.

Un valor vacío (`EPLSYNC_TORRENT_TRACKERS=`) genera magnets sin `tr`, que el
cliente puede resolver mediante DHT si está habilitado y hay pares disponibles.
El ejemplo de Compose usa `${VARIABLE-defecto}`, sin `:`, para respetar ese valor
vacío. Los cambios requieren reiniciar la aplicación (o recrear el contenedor
con el nuevo entorno), pero no reimportar el catálogo ni modificar la BD.

## URL del ZIP del catálogo

La URL predeterminada para importar y previsualizar se configura en `application.yaml`
y puede sobrescribirse en `application-local.yaml`:

```yaml
eplsync:
  catalog:
    zip-url: "https://epublibre.org/rssweb/csv/epub.zip"
```

El parámetro opcional `url` de los endpoints de importación tiene prioridad sobre
este valor. En Docker puede sobrescribirse con `EPLSYNC_CATALOG_ZIPURL`.

## Idioma en la API

`language` se devuelve como el código de `Language.getIsoCode()` (`es`, `en`, `ca`, etc.).
`OTRO` se representa como `other`, un valor propio de la aplicación, no un código ISO 639-1.
Los filtros aceptan `language=es` y conservan compatibilidad con `language=ESPANOL`.
Los valores no reconocidos se rechazan; la importación CSV mantiene su conversión habitual.
El placeholder `{language}` también produce el código en nombres y etiquetas torrent.
Los valores almacenados en la base de datos no cambian; no hace falta reimportar.

## Tamaño de página

Los endpoints de catálogo y torrent aceptan `page >= 0` y `size > 0`, sin límites
artificiales de 100, 500 o 2000 elementos. Spring tampoco recorta `size` a 2000.
Los parámetros usan enteros de 32 bits (máximo representable: 2147483647).
Los valores por defecto y el comportamiento sin paginación se mantienen.

Por ejemplo, `POST /api/torrent/books`
selecciona hasta 10000 libros; no inicia 10000 envíos simultáneos. `batchSize`,
`concurrency` e `interval` siguen controlando la ejecución. En listados GET, una
página grande genera una respuesta mayor y requiere más memoria y tiempo.

## Filtrar el catálogo por eplId

El filtro compartido `eplId` admite un identificador positivo y aplica coincidencia
exacta. Se combina mediante AND con los demás filtros y funciona en
`GET /api/catalog/books`, `GET /api/catalog/magnets` (también `/export`) y
`POST /api/torrent/books`.

```bash
curl -s 'http://localhost:8088/api/catalog/books?eplId=32' | jq
curl -s 'http://localhost:8088/api/catalog/books?eplId=32&size=20' | jq
```

La búsqueda devuelve un listado vacío si no hay coincidencias; la ruta individual
`GET /api/catalog/books/32` sigue devolviendo un objeto o `404` si no existe.
Un `eplId` no numérico, fuera del rango de `Long`, cero o negativo devuelve `400`.

### Metadatos del catálogo actual

Se conserva un único registro en `catalog_metadata`, con la última importación aplicada:
URL de origen, nombre y fecha original del CSV en el ZIP, fecha de importación UTC,
modo (`UPDATE` o `REPLACE`), filas totales (correctas + rechazadas), libros insertados,
modificados y sin cambios, errores, duración total en milisegundos y SHA-256 del CSV
extraído, antes de normalizarlo. La duración incluye descarga y extracción.
La fecha del ZIP se conserva sin atribuirle una zona horaria. También aparece en el log
de extracción; si falta, se indica como desconocida.

El origen incluye `sourceType` (`URL`, `LOCAL_FILE` o `SAVED_ZIP`) y
`sourceArchiveName` (nombre del ZIP). La URL original se conserva cuando existe,
incluso si se reutiliza el ZIP. Aplicar una previsualización conserva la opción elegida al crearla: URL, archivo local
o ZIP guardado. Recalcular y reiniciar el servidor también conservan esa selección.
En la interfaz, «Origen» muestra la URL, «Archivo local» o «ZIP guardado en el
servidor», acompañado del nombre disponible. Las importaciones antiguas no permiten
reconstruir si se reutilizó el ZIP ni su nombre; sin URL se muestra «Archivo local».

Los libros y sus metadatos se guardan en la misma transacción. Una previsualización o
un fallo no reemplazan los metadatos. Las actualizaciones completadas con filas
rechazadas guardan el recuento de errores; la eliminación de ausentes exige
una importación sin errores. No se conserva historial de importaciones.

`GET /api/catalog/import/metadata` devuelve `{ "metadata": null }` si aún no existe
información, o el objeto con esos campos. Las respuestas de actualización y reemplazo del catálogo
incluyen también `metadata`. El reinicio completo elimina este registro. Ajustes → Base de datos muestra un único resumen en
«Catálogo actual»; los avisos de éxito desaparecen a los cinco segundos o al cerrarlos.

### Importar desde URL, ZIP local o archivo guardado

En Ajustes → Base de datos, «Importar catálogo» abre un asistente. Primero se elige
una URL (editable, inicialmente la configurada), un ZIP local o el ZIP guardado.
Después se selecciona Previsualizar o Actualizar catálogo. El asistente se cierra
y los resultados aparecen en la sección de importación.

Solo se conserva **un ZIP**, durante 24 horas por defecto
(`eplsync.catalog.import.retention`). El asistente muestra su fecha de CSV,
fecha de guardado, caducidad, tamaño y SHA-256 copiable. Iniciar una descarga o
carga nueva elimina el archivo anterior. Importarlo, descartar la previsualización
o cerrar su resumen con la X no elimina el ZIP ni renueva su caducidad.

La previsualización ofrece Actualizar catálogo y Descartar. Si los datos locales
cambian, Recalcular actualiza el resumen desde el mismo ZIP. Aplicar no descarga
otra copia. Los CSV extraídos se borran al terminar cada operación. El reinicio
completo de la base de datos también elimina el ZIP guardado.

Consulta los [endpoints y la configuración](API.md#asistente-de-importación-y-zip-guardado).

«Catálogo actual» y el asistente muestran el SHA-256 del ZIP. Los metadatos conservan
`sourceZipSha256` (ZIP) y `sourceSha256` (CSV); este último no se muestra en la interfaz.
Las importaciones anteriores sin hash del ZIP muestran «Desconocida» hasta una nueva
importación; no se utiliza el hash del CSV como sustituto.
