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
