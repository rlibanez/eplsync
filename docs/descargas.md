# Estado e historial de descargas

[Guías](README.md) · [Inicio](../README.md)

## Estado de las descargas

**Descargas → Estado** muestra los registros de EPL Sync. Un libro puede tener
varias revisiones o hashes. Utiliza los filtros y los controles **Ordenar** y
**Columnas** para adaptar la tabla.

Los estados de envío indican el resultado de enviar un torrent. Los estados como
En cola, Descargando, Pausado o Descargado proceden de las comprobaciones del cliente.
**No encontrado** indica que el torrent no aparece en la última comprobación;
no implica que sus archivos se hayan borrado.

En la columna **Acciones** puedes actualizar el estado o abrir **Eliminar** para
un registro. Al seleccionar varios registros, aparecen esas mismas acciones para
la selección completa, aunque pertenezcan a libros distintos.

**Actualizar estado** requiere `TORRENT_SYNC` y consulta juntos los hashes elegidos.
Eliminar registros de EPL Sync requiere `DOWNLOADS_DELETE`; eliminar torrents
requiere `TORRENT_CLEANUP`, y eliminar también sus archivos requiere además
`TORRENT_FILES_DELETE` y confirmación explícita. Se conservan las comprobaciones
del cliente de destino, las protecciones de archivos compartidos y el registro en
Eventos. Eliminar un registro local no cancela sus solicitudes de limpieza pendientes.

## Sincronizar con el cliente

- **Previsualizar sincronización:** muestra el resultado previsto sin aplicar cambios.
- **Sincronizar con el cliente:** actualiza los registros según el estado de qBittorrent.

El resultado se presenta en un informe plegable, con resumen y tablas de registros
de descargas y torrents ignorados. Las limpiezas pendientes que correspondan se
procesan también durante una sincronización real; la previsualización no solicita
borrados. El informe distingue los resultados de sincronización y limpieza.

La sincronización puede reconstruir registros de torrents identificables mediante
el catálogo actual. No recupera necesariamente información histórica eliminada.

## Historial asociado a un libro

La ficha individual muestra sus revisiones, hashes, estado y fechas cuando tienes
permiso para ver el historial. Puedes seleccionar registros y:

- **Actualizar estado:** consulta al cliente los hashes seleccionados.
- **Eliminar:** abre un formulario para elegir si se elimina el registro de EPL Sync,
 el torrent del cliente o también sus archivos.

Las opciones disponibles dependen de tus permisos. Borrar el registro local no
elimina el torrent del cliente; borrar solo el torrent no elimina sus archivos.
La combinación elegida requiere confirmación, y la aplicación puede bloquear
operaciones sobre destinos o archivos compartidos.

Si se elimina un torrent del cliente y se conserva su historial, su ausencia
confirmada queda como **No encontrado**. Las solicitudes de limpieza relacionadas
se actualizan para evitar repetir operaciones ya resueltas.

## Descargar los EPUB al navegador

En Estado y en el historial de descargas de cada libro, el icono **Descargar EPUB**
de la columna **Acciones**, con tooltip, permite descargar los archivos completos del torrent. Requiere el permiso
**Descargar archivos EPUB**, además del permiso para consultar esa vista.
La columna Acciones se puede ocultar y reordenar desde **Columnas**, pero no sirve
como criterio de ordenación. Si hay un único EPUB, se descarga directamente.
Si hay varios, se abre un selector para elegir el archivo. No se modifica el
contenido del cliente torrent ni se envía a Calibre.

En **Estado**, también puedes seleccionar registros mediante la primera columna
y pulsar **Descargar**. La selección corresponde a la página actual y se limpia
al cambiar filtros, ordenación o página. Un único EPUB se descarga directamente;
varios archivos o registros se reúnen en un ZIP, sin modificar sus originales.
Los nombres repetidos se distinguen con un sufijo numérico. Antes de iniciar,
se comprueba toda la selección: si algún registro no tiene EPUB completos y
accesibles, se indican los registros afectados y no se inicia la descarga.
El límite es de 1000 registros y 1000 archivos por descarga.

Configura en **Ajustes > Torrent > Archivos descargados** las dos carpetas:

- **Carpeta base en el cliente torrent**: ruta vista por qBittorrent, por ejemplo `/downloads`.
- **Carpeta accesible en EPL Sync**: la misma carpeta vista por EPL Sync, por ejemplo `/app/downloads`.

También puedes definir `EPLSYNC_TORRENT_FILES_REMOTE_ROOT` y
`EPLSYNC_TORRENT_FILES_LOCAL_ROOT`. En Docker, la ruta local predeterminada es
`/app/downloads`. La carpeta base de qBittorrent vacía desactiva el acceso,
aunque la ruta local esté configurada. En ejecución nativa, configura también
la ruta local absoluta correspondiente en `application.yaml` o en Ajustes.
Las subcarpetas y los nombres se obtienen de qBittorrent para cada hash al descargar.
Solo ADMIN puede cambiar estas rutas desde Ajustes.

En Docker, añade un montaje a `volumes` del servicio de EPL Sync:

```yaml
- /ruta/real/de/descargas:/app/downloads:ro
```

Esa ruta del host debe contener los mismos archivos que la carpeta `/downloads`
de qBittorrent. El montaje `:ro` concede solo lectura. En instalaciones nativas,
usa la ruta absoluta de la carpeta accesible al proceso de Java.
Los archivos fuera de la carpeta configurada, incompletos, inaccesibles o
con enlaces simbólicos en su ruta se rechazan. Si el archivo se mueve mediante
qBittorrent, se consulta su ubicación actual.

Desde **Descargas > Estado**, **Buscar actualizaciones** abre el mismo asistente
que **Descargas > Actualizaciones**. Los resultados y los envíos se siguen
consultando en esa vista; el acceso requiere los permisos de catálogo y
sincronización con el cliente torrent.
