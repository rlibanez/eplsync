# Actualización de revisiones y limpieza

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Actualizar revisiones de libros gestionados

La selección compara el catálogo actual con las descargas del **cliente y URL
configurados**. Incluye libros con una revisión superior a la mayor revisión
presente o enviada y con hashes válidos distintos. No incluye libros desconocidos,
revisiones iguales/inferiores, ni libros con envíos pendientes (incluidos jobs
pausados). Una revisión nueva ya enviada/presente no se vuelve a encolar. Un envío
incierto de esa revisión (`UNKNOWN`) requiere reconciliar su estado antes de
volver a seleccionarlo. Los intentos `ERROR` se pueden volver a seleccionar.

Previsualización de solo lectura: no consulta qBittorrent, no sincroniza y no
escribe registros. Usa el último estado guardado; si se necesita información
actual del cliente, ejecutar antes `POST /api/torrent/downloads/sync`.

```bash
curl -s 'http://localhost:8088/api/torrent/updates?page=0&size=50' | jq
curl -s 'http://localhost:8088/api/torrent/updates?eplId=1234&multipleHashes=all' | jq
```

La respuesta contiene `items` y `meta`. Cada candidato incluye `eplId`, `title`,
`catalogRevision`, `existingDownloads` (ID, revisión, hash y estado anteriores)
y `targetHashes`. `includeNotFound=true` permite actualizar también libros cuyo
único historial elegible ya no está en el cliente. `ERROR` y `UNKNOWN` no bastan
por sí solos para considerar un libro gestionado. `multipleHashes` acepta
`all`, `first` o `skip`; si falta, utiliza el valor bulk de `application.yaml`.

Crear el job (sin `eplId` selecciona **todos los candidatos**, no solo la página
mostrada en la previsualización):

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates?eplId=1234' \
  -H 'Content-Type: application/json' \
  -d '{"previousVersions":"keep","multipleHashes":"all","concurrency":2,"interval":"100ms"}' | jq
```

Devuelve `202`, el job bulk y `Location: /api/torrent/jobs/{jobId}`. El cuerpo es
opcional y admite `options` (como el envío individual, excepto `hash`),
`batchSize`/`batch-size`, `concurrency`, `interval` y `multipleHashes`. Los valores
omitidos heredan los defaults de descarga y bulk. Se mantienen los endpoints de
progreso, items, pausa, reanudación y cancelación de jobs. Si no hay candidatos,
se crea un job vacío ya completado. Repetir la petición no encola nuevamente
libros con una actualización pendiente. El catálogo, revisión, hashes y opciones
quedan congelados en el job; una importación posterior no cambia sus objetivos.

`previousVersions` se guarda en el plan y **no elimina nada durante el envío**:

| Valor | Limpieza posterior |
| --- | --- |
| `keep` (por defecto) | Conserva torrents y archivos anteriores. |
| `removeTorrent` | Elimina solo los torrents anteriores. |
| `removeTorrentAndFiles` | Solicita a qBittorrent eliminar torrents y sus archivos. |

Consultar el plan y ejecutar su limpieza por separado:

```bash
curl -s 'http://localhost:8088/api/torrent/updates/JOB_ID' | jq
curl -s -X POST 'http://localhost:8088/api/torrent/updates/JOB_ID/cleanup' | jq
```

El GET muestra la política, los objetivos congelados y el estado por registro
anterior. El POST consulta el estado **actual** de qBittorrent: antes de borrar,
todos los hashes seleccionados de la nueva revisión deben estar completos.
Identifica torrents híbridos por sus aliases y usa el ID remoto al borrar.
Solo considera versiones anteriores guardadas en el plan; nunca borra por
nombre o categoría. Conserva el historial y las fechas de descarga en EPLsync.

Estados de limpieza:

- `KEPT`: política de conservación; no hay borrado ni consulta al cliente.
- `WAITING`: falta la nueva revisión o todavía no está completa.
- `BLOCKED`: torrent compartido con otro libro, identidad coincidente con la
  nueva revisión o rutas de archivos compartidas/no verificables.
- `REQUESTED`: eliminación solicitada pero todavía no se ha confirmado que el
  torrent haya desaparecido. También se usa ante respuestas de red inciertas.
- `REMOVED`: ausencia confirmada; el historial anterior queda `NOT_FOUND`.

La limpieza usa una instantánea remota y otra para confirmar los borrados, no
una consulta de estado por libro. Al repetirla reevalúa `WAITING` y `BLOCKED`,
y comprueba los `REQUESTED` sin repetir automáticamente la escritura. Para
reintentar explícitamente una eliminación sin confirmar:

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates/JOB_ID/cleanup?retryUnconfirmed=true' | jq
```

Este reintento vuelve a comprobar completitud y protecciones. El sync habitual
nunca ejecuta limpiezas. Si hay envíos o sync en curso, la limpieza devuelve
`409`; si se cambió el destino, exige restaurarlo. La coordinación es por
instancia de EPLsync (una instancia por base de datos).

Para borrar archivos se comparan las rutas `content_path` reportadas por el
cliente: solo rutas POSIX absolutas, sin componentes `..`, y sin coincidencias
ni solapamientos con otros torrents. Si una ruta no se puede comprobar, se bloquea
el borrado de datos. Esta comprobación no inspecciona enlaces simbólicos o hard
links del sistema de archivos remoto, ni puede evitar cambios simultáneos hechos
fuera de EPLsync. `REMOVED` confirma la ausencia del torrent, no una verificación
física del borrado del archivo; el tratamiento final de los datos depende de
qBittorrent. No hay limpieza automática ni cambios de política implícitos.

## Limpieza global de actualizaciones

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates/cleanup' | jq
```

Procesa los planes con política `removeTorrent` o `removeTorrentAndFiles` y
registros `WAITING`, `BLOCKED` o `REQUESTED`. Excluye `KEPT` y `REMOVED`.
Reevalúa las condiciones de seguridad y comprueba ausencias para los borrados
sin confirmar. No repite estos últimos salvo petición explícita:

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates/cleanup?retryUnconfirmed=true' | jq
```

Comparte una instantánea inicial del cliente entre todos los jobs, los índices
de hashes/rutas y, si se intentaron borrados, una única consulta final de
confirmación. Sin trabajos aplicables no consulta al cliente. La exclusión mutua
con envíos y sync cubre toda la operación; las políticas de los jobs no cambian.

La respuesta contiene `selectedJobs`, `failedJobs`, `checked`, `removed`,
`waiting`, `blocked`, `requested` y `jobs`. Cada elemento de `jobs` contiene
`jobId`, los mismos contadores de registros y `error` (nulo cuando no hay error).
Los contadores describen solo los registros pendientes seleccionados en esta
petición, no todo el historial ni torrents únicos. Un torrent compartido por
varios registros se borra como máximo una vez. `failedJobs` puede coincidir con
registros `requested`: una escritura incierta no se declara eliminada.

Un destino incompatible se informa en el resultado del job y no se toca.
Los errores de un job no impiden procesar los demás. Si falla la consulta inicial
al cliente, falla la petición sin realizar borrados. Si falla la confirmación
final, los registros quedan sin confirmar y la respuesta incluye el error.

La limpieza global protege los torrents que son objetivos de otros planes
seleccionados y bloquea políticas incompatibles sobre el mismo torrent. En una
cadena de revisiones, puede ser necesario repetir la limpieza una vez resueltos
los planes anteriores. Sigue disponible la limpieza individual:
`POST /api/torrent/updates/{jobId}/cleanup`.
