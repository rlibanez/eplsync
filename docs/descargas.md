# Historial y sincronización de descargas

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Datos persistidos de los trabajos

Cada elemento preparado guarda un comando de envío con hash, magnet, nombre ya
resuelto, ruta, inicio y opciones del cliente. Del libro conserva únicamente ID,
revisión y título, más los campos utilizados por los patrones de etiquetas.
No duplica la sinopsis, los enlaces ni las portadas salvo que un patrón de etiqueta
utilice explícitamente ese campo. Los valores necesarios quedan congelados al
preparar el trabajo: modificar o borrar el catálogo no cambia el envío pendiente.
Esta reducción no elimina historial de descargas ni limpiezas pendientes.

### Retención de trabajos finalizados

Por defecto, los trabajos completados o cancelados se conservan 90 días desde su
última actualización. La comprobación se ejecuta cada hora y puede desactivarse o
configurarse mediante `EPLSYNC_TORRENT_BULK_RETENTION_ENABLED`,
`EPLSYNC_TORRENT_BULK_RETENTION_DAYS` y `EPLSYNC_TORRENT_BULK_RETENTION_INTERVAL`.
Se conservan los trabajos en cola, activos, pausados y pendientes de reintento, así
como cualquiera con elementos pendientes/en curso o limpiezas en estado `WAITING`,
`BLOCKED` o `REQUESTED`, incluso cuando la integración torrent está deshabilitada.

Cada ciclo elimina hasta 20 trabajos caducados. Cada trabajo se elimina completo
en su propia transacción, mediante borrados SQL sin cargar sus comandos en memoria.
También se eliminan su plan de actualización y sus limpiezas ya finalizadas.
Una transacción tiene un presupuesto SQLite de 10 segundos; un fallo revierte ese
trabajo y se reintenta en otro ciclo. El ciclo deja de iniciar transacciones al
alcanzar 10 segundos. No se publica un trabajo con sus elementos parcialmente
eliminados. El tamaño de la transacción depende del número de elementos del trabajo.

El historial asociado a los libros y los eventos permanecen intactos; los eventos
siguen su propia retención. No se contacta con qBittorrent ni se borran archivos.
Un trabajo eliminado deja de estar disponible en Trabajos y mediante su API;
los enlaces antiguos a su detalle devuelven el error habitual de trabajo inexistente.
SQLite reutiliza el espacio liberado: la purga no ejecuta `VACUUM` automáticamente.

## Historial de descargas y sincronización manual

Los envíos individuales y bulk registran cada torrent en `torrent_downloads`.
No se añaden columnas de descarga a `catalog_books` ni se borra el historial al
reimportar el catálogo. Se conserva la revisión del snapshot enviado, aunque el
catálogo cambie durante la descarga.

Estados iniciales:

- `SUBMITTED`: el cliente aceptó el envío; no implica descarga completada.
- `ALREADY_EXISTS`: el hash ya estaba en el cliente, pendiente de sincronizar su estado.
- `ERROR`: rechazo o fallo confirmado del envío.
- `UNKNOWN`: resultado incierto, por ejemplo un timeout o una interrupción. Antes
  de realizar la llamada se persiste este estado para poder reconciliar un cierre
  inesperado de EPLSync. No se reenvía desde el endpoint de sincronización.

Los elementos bulk omitidos sin enviar un hash no crean registros de descarga.
La identidad es instancia del cliente + libro + hash. Cada archivo/revisión con
un hash distinto conserva su registro. Reenviar el mismo hash no duplica el registro
ni cambia su revisión original, origen o evidencia de finalización.

```bash
curl -X POST 'http://localhost:8088/api/torrent/downloads/sync' -H 'Content-Type: application/json' -d '{"dryRun":false}'
curl 'http://localhost:8088/api/torrent/downloads?eplId=32&page=0&size=20&sort=revision,desc'
```

La sincronización es exclusivamente manual. Consulta una instantánea completa
mediante `GET /api/v2/torrents/info`, sin filtros de categoría, y:

- Actualiza registros conocidos por hash, incluidos los ya completados. En qBittorrent
  compara `hash`, `infohash_v1` e `infohash_v2`: un torrent híbrido cuenta una sola
  vez en `remote.total` y `remote.ignored`. Conserva el hash original del registro; un
  falso `NOT_FOUND` por esta diferencia se corrige al repetir el sync.
- Descubre torrents cuyos hashes coincidan con `links` del catálogo actual.
- Ignora torrents que no coinciden con el historial ni con el catálogo.
- Traduce estados a `QUEUED`, `DOWNLOADING`, `PAUSED`, `CHECKING`, `DOWNLOADED`,
  `ERROR` o `UNKNOWN`. Un torrent detenido no se considera completo por estar detenido.
- Marca `NOT_FOUND` cuando desaparece un torrent previamente aceptado/observado.
  Un intento rechazado o incierto nunca observado conserva `ERROR`/`UNKNOWN` si
  sigue ausente. Si reaparece, vuelve a reflejar el estado del cliente.
- Conserva `completedAt` aunque el torrent desaparezca. Esto acredita una descarga
  histórica; no garantiza que el archivo siga en disco. Si se completó y eliminó
  entre consultas sin que lo observásemos, no se inventa una finalización.

Una respuesta inválida o un fallo de conexión no modifica estados ni fechas. La
reconciliación se aplica en una transacción. No añade torrents, renombra archivos
ni modifica qBittorrent. Mientras hay envíos en curso u otra sincronización, devuelve
`409`; se puede repetir después. Los envíos que lleguen durante una sincronización
esperan a que termine. La coordinación presupone una instancia de EPLSync por BD.

La petición exige un cuerpo JSON con `dryRun` booleano. Con `true` calcula el resultado
sin escribir ni fechas de seguimiento. `includeDetails=true` añade `items` e
`ignoredTorrents`; omitido, solo devuelve el resumen. No admite parámetros en la URL.
La respuesta separa los torrents (`remote`), las acciones de registros (`records`)
y los resultados destacados (`outcomes`), además de `dryRun`, `applied`, `client`,
`clientInstanceId` y `checkedAt`. Véase [el contrato completo](API.md#11-sincronización-de-descargas).

Cada registro contiene `id`, `eplId`, `revision`, `hash`, `client`,
`clientInstanceId`, `status`, `origin`, `createdAt`, `requestedAt`, `submittedAt`,
`discoveredAt`, `completedAt`, `lastCheckedAt`, `lastSeenAt` y `lastError`.

- `origin`: `EPLSYNC` o `DISCOVERED`, inmutable.
- `requestedAt`: primer intento de envío desde EPLSync; `submittedAt`: primera
  aceptación confirmada. Pueden ser nulos en registros descubiertos.
- `discoveredAt`: detección de un registro añadido manualmente al cliente.
- `completedAt`: fecha indicada por el cliente o, si no la facilita, fecha de la
  primera observación de descarga completa.
- `lastCheckedAt`: última comprobación correcta, incluso si estaba ausente;
  `lastSeenAt`: última vez que constaba presente.
- La instancia se identifica mediante SHA-256 del tipo y URL base (sin barra final).
  Cambiar la URL identifica otra instancia; no se actualizan registros de la anterior.
  No se exponen credenciales ni URL de conexión en las respuestas.

#### Filtros de descargas

`GET /api/torrent/downloads` siempre devuelve `items` y `meta`, con paginación.

| Parámetro | Uso |
| --- | --- |
| `eplId`, `hash`, `revision` | Coincidencia exacta; hash sin distinguir mayúsculas |
| `status` | Uno o varios estados separados por comas |
| `origin` | `EPLSYNC`, `DISCOVERED` o ambos separados por comas |
| `client`, `clientInstanceId` | Tipo e instancia del cliente |
| `completed` | `true` si existe `completedAt`, incluso en `NOT_FOUND`; `false` en otro caso |
| `createdAtFrom` / `createdAtTo` | Intervalo inclusivo ISO 8601 con zona horaria |
| `requestedAtFrom` / `requestedAtTo` | Intervalo de primer intento |
| `submittedAtFrom` / `submittedAtTo` | Intervalo de primera aceptación |
| `completedAtFrom` / `completedAtTo` | Intervalo de finalización |
| `discoveredAtFrom` / `discoveredAtTo` | Intervalo de descubrimiento |
| `lastCheckedAtFrom` / `lastCheckedAtTo` | Intervalo de comprobación |
| `lastSeenAtFrom` / `lastSeenAtTo` | Intervalo de presencia |
| `page`, `size` | Desde 0; tamaño por defecto 20, sin máximo de aplicación |
| `sort` | Por defecto `createdAt,desc`; repetible para varios criterios |

Los filtros diferentes se combinan con AND; los estados/orígenes de una lista con
OR. Las fechas nulas no satisfacen filtros de intervalo. Parámetros desconocidos,
valores inválidos e intervalos invertidos devuelven `400`. Se admite ordenar por
identificadores, revisión, hash, estado, origen, cliente o cualquiera de las fechas;
se añade `id` para estabilizar el orden.

#### Resumen en el catálogo

Tanto `GET /api/catalog/books/{eplId}` como el listado añaden `download.items`,
ordenado por revisión y fecha de creación descendentes. Sin registros, `items: []`.
Se mantienen todos los campos anteriores del libro y no se consulta al cliente
para responder. Los datos reflejan el último envío/sincronización guardado.

```json
{
  "download": {
    "items": [
      {"id": "uuid-2", "revision": 2.0, "status": "DOWNLOADING", "completed": false},
      {"id": "uuid-1", "revision": 1.5, "status": "NOT_FOUND", "completed": true}
    ]
  }
}
```

`completed` se calcula a partir de `completedAt`. El detalle con cliente, hash y
fechas está en `/api/torrent/downloads?eplId=32`. El resumen usa consultas agrupadas
(hasta 500 libros por consulta), sin una consulta adicional por cada libro.

La primera sincronización también permite incorporar torrents anteriores a esta
funcionalidad, con origen `DISCOVERED`. Solo puede asociar revisiones antiguas si
ya estaban registradas: no se deduce una revisión del nombre del archivo ni se
reconstruyen hashes históricos que ya no estén en el catálogo.

## Resumen de descargas por estado

`GET /api/torrent/downloads/summary` devuelve `total` y `byStatus`, con todos los
estados (incluidos los que tienen contador cero). Cuenta registros de descarga,
no libros ni torrents únicos: varias revisiones de un libro son varios registros.

```bash
curl -s 'http://localhost:8088/api/torrent/downloads/summary' | jq
curl -s 'http://localhost:8088/api/torrent/downloads/summary?eplId=1234&origin=EPLSYNC' | jq
```

Admite los mismos filtros del listado de descargas, incluidos `client`,
`clientInstanceId`, `status`, `completed` y los rangos de fechas. Sin filtros
incluye todos los registros de la base de datos, también los de otros destinos.
No admite `page`, `size` ni `sort`: agrega todo el conjunto filtrado en una sola
consulta SQL, sin cargar las entidades. `total` es la suma de `byStatus`.

Es una consulta de solo lectura sobre el último estado guardado en EPLsync; no
consulta qBittorrent ni ejecuta un sync. Para actualizar esos estados, ejecutar
antes `POST /api/torrent/downloads/sync` con cuerpo `{"dryRun":false}`. El filtro `completed=true` conserva su
significado habitual (existe `completedAt`); puede incluir registros `NOT_FOUND`
que terminaron de descargarse anteriormente.


## Historial en el catálogo

Con `BOOK_HISTORY_READ`, cada libro incluye hasta 20 registros recientes en
`download.items`, el total en `download.totalItems` y los estados presentes en
todo su historial en `download.statuses`. La ficha consulta el historial completo
por páginas mediante `GET /api/catalog/books/EPL_ID/history?page=0&size=20`
(máximo 1000 por página). Sin ese permiso no se revelan registros, totales ni estados.

### Informes de sincronización y límites de memoria

La consulta completa a qBittorrent se mantiene. EPL Sync interpreta cada torrent directamente desde la respuesta, omitiendo los campos que no necesita. `EPLSYNC_TORRENT_MAX_RESPONSE_SIZE` limita los bytes recibidos a 256 MiB y `EPLSYNC_TORRENT_MAX_REMOTE_TORRENTS` a 500.000 torrents por listado. Las respuestas auxiliares se limitan a 1 MiB. El tiempo de petición también cubre la recepción del cuerpo. Un listado truncado, inválido o que supera los límites no se aplica parcialmente a los registros de EPL Sync.

Los registros y el catálogo se recorren en lotes de 500. El resumen de sincronización no contiene todas las filas: con `includeDetails=true` devuelve `detailsId`. Los detalles se consultan en `GET /api/torrent/downloads/reports/{detailsId}/books` o `/ignored`, con `page`, `size` (1–1000), `sort` (criterios separados por `;`), `search`, `action` y `outcome`. Estas páginas conservan el resultado de esa ejecución, incluido el de una previsualización; no vuelven a sincronizar ni consultan el estado posterior a la limpieza.

Los detalles se guardan en SQLite temporal, separado de la base de datos principal y accesible solamente al usuario que creó el informe, con permiso `TORRENT_SYNC`. Se retienen como máximo cuatro informes durante 30 minutos, con 128 MiB por informe y 64 KiB por fila. Un informe nuevo puede retirar el más antiguo terminado; los informes desaparecen al reiniciar la aplicación. Las consultas tienen un máximo de 10 segundos y se admite una a la vez, sin acumular consultas en cola. La interfaz conserva el resumen al navegar y solicita únicamente la página visible.
