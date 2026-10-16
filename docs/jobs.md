# Trabajos de envío

[Guías](README.md) · [Inicio](../README.md)

Los trabajos controlan los envíos de libros y actualizaciones a qBittorrent.
Un envío aceptado no implica una descarga terminada.

## Consultar trabajos

En **Descargas → Trabajos** puedes filtrar por tipo, estado, usuario y fechas.
La tabla muestra quién inició cada trabajo. Utiliza **Ordenar** y **Columnas**
para adaptar su presentación.

Abre un trabajo para consultar su informe, opciones de envío, progreso, resultados
por libro y política de versiones anteriores. La tabla de elementos permite
filtrar por estado, ordenar, ajustar columnas y consultar los errores.

## Fases de una actualización

Los trabajos de actualización reúnen dos fases: **Envío** y **Limpieza**.
Al finalizar los envíos, el trabajo sigue **Pendiente de limpieza** si quedan
borrados por resolver. Un bloqueo de seguridad se muestra en el informe y no
da el trabajo por terminado. La ausencia del torrent debe confirmarse antes
de considerar completado su borrado.

El trabajo pasa a **Terminado** cuando concluyen ambas fases. Si no se solicitó
borrado de revisiones anteriores, termina al finalizar los envíos, sin esperar
a que se descarguen los libros.

## Pausar, reanudar y cancelar

Los controles disponibles dependen del estado del trabajo y de tus permisos.

- **Pausar:** detiene los siguientes envíos y las nuevas solicitudes de limpieza; las operaciones en curso pueden terminar.
- **Reanudar:** continúa los elementos pendientes.
- **Cancelar:** cancela los envíos y las limpiezas pendientes; no revierte borrados ya solicitados.

Pausar o cancelar un trabajo no detiene ni elimina los torrents ya enviados.
Si se desactiva la integración o cambia el destino o la autenticación del cliente,
los trabajos activos pueden pausarse antes del siguiente envío; revisa su informe
antes de reanudarlos.

## Conservación

Los trabajos completados o cancelados se conservan por defecto durante 90 días
desde su última actualización. La retención conserva los que tienen operaciones
o limpiezas pendientes. Puede configurarse mediante las
[variables de instalación](variables-entorno.md).

Eliminar trabajos por retención no borra el historial de descargas, los eventos,
los torrents ni sus archivos.

## Reintentar elementos

En las fichas de **Trabajos de descargas** y **Trabajos de Calibre** puedes usar
**Reintentar fallidos** o marcar filas y pulsar **Reintentar seleccionados**.
El trabajo debe estar terminado o cancelado y sin operaciones en curso. Para
continuar un trabajo pausado, utiliza **Reanudar**.

El diálogo resume los envíos, verificaciones, esperas y limpiezas recuperables.
Los elementos sin nada pendiente o con problemas se excluyen y se indica el
motivo. Los cambios de conexión o archivo requieren confirmación; los borrados
y los envíos inciertos requieren restaurar su conexión original.

El nuevo trabajo conserva la revisión y las opciones originales y enlaza con su
padre y con el trabajo inicial. El original mantiene sus resultados y muestra
los reintentos creados. Repetir una petición no crea dos trabajos; para volver a
intentar un elemento ya transferido a otro trabajo, continúa desde ese reintento.

Antes de enviar se comprueba si el torrent o la revisión con EPUB ya existen.
Un envío incierto de Calibre solo se verifica: si no puede confirmarse, queda
pausado y no se vuelve a enviar automáticamente. Una limpieza pendiente no
repite el envío del libro. Los trabajos de Calibre que esperan descargas también
reconocen los reintentos del trabajo torrent, siempre para la revisión original.

Los reintentos comprueban de nuevo los permisos de envío y borrado. Cancelar una
limpieza iniciada antes de esta funcionalidad puede haber descartado sus opciones;
en ese caso, vuelve a seleccionar explícitamente las revisiones que quieras borrar.

## Limpieza manual de revisiones antiguas

Consulta [Limpiar revisiones antiguas](limpieza-revisiones.md) para preparar una limpieza con previsualización, selección y seguimiento por trabajo.

Las fichas de trabajos de Descargas y Calibre incluyen los enlaces **Anterior** y **Siguiente**, también para trabajos de limpieza. Siguen los filtros y el orden de la lista de origen y funcionan al cruzar páginas. Al volver a la lista se recupera la página del trabajo consultado y sus filtros. Al abrir una ficha directamente se utiliza el orden por fecha de creación descendente.

En **Enviar actualizaciones**, la acción sobre los torrents antiguos y la casilla **Borrar también el registro de EPL Sync** son independientes. La casilla está desmarcada por defecto. El momento elegido se aplica también al borrado solo del registro: tras aceptar el envío o tras completar la nueva descarga. Si se eliminan torrent y registro, este último se conserva hasta confirmar la ausencia del torrent. Los reintentos mantienen ambas opciones. Borrar el registro requiere el permiso de eliminación de registros de Descargas; no amplía los permisos para borrar torrents o archivos.
