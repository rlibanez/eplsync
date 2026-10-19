# Trabajos de descargas y Calibre

[Guías](README.md) · [Inicio](../README.md)

Los trabajos de Descargas controlan el envío, la descarga y, en las actualizaciones, la limpieza de versiones anteriores. Un envío aceptado no implica una descarga terminada: el trabajo permanece **Esperando descargas** hasta comprobar su resultado. El informe separa las cifras del envío y de la descarga.

Las descargas se comprueban automáticamente, sin reenviar torrents. El intervalo inicial es de 30 segundos y se configura en **Ajustes → Tareas → Comprobaciones de trabajos → Seguimiento de trabajos de descarga**, con un mínimo de 10 segundos y sin reiniciar la aplicación. Un error o un torrent ausente aparece por elemento y se vuelve a comprobar; puedes corregirlo en el cliente u **Omitir seguimiento** si no quieres esperar esa descarga. Omitir no borra archivos ni torrents y no autoriza la limpieza de una revisión de sustitución que no esté verificada.

Pausar o cancelar detiene las operaciones pendientes de EPL Sync, sin pausar ni borrar los torrents ya enviados al cliente. La pantalla inicial incluye tanto los trabajos que esperan descargas como el número de descargas pendientes. Los trabajos ya cerrados antes de esta funcionalidad conservan su estado.

## Consultar trabajos

En **Descargas → Trabajos** puedes filtrar por tipo, estado, usuario y fechas.
La tabla muestra quién inició cada trabajo. Utiliza **Ordenar** y **Columnas**
para adaptar su presentación. El botón **= / ≠** de Tipo y Estado permite incluir
o excluir valores; vaciar un campo o pulsar **Limpiar** restablece la inclusión.

Abre un trabajo para consultar su informe, opciones de envío, progreso, resultados
por libro y política de versiones anteriores. La tabla de elementos permite
filtrar por estado, ordenar, ajustar columnas y consultar los errores. En el
trabajo de Calibre, **Detalle contiene** busca cualquier fragmento del mensaje
en todo el trabajo y se combina con Estado. Pulsar un Estado o Detalle de una fila
aplica su filtro. La columna opcional **Orden de envío** conserva la posición
original, mientras **#** numera las filas según el orden y filtro actuales.

## Fases de una actualización

Los trabajos de actualización reúnen tres fases: **Envío**, **Descarga** y **Limpieza**.
Al terminar las descargas, el trabajo sigue **Pendiente de limpieza** si quedan borrados por resolver. El momento elegido para el borrado se respeta: una limpieza inmediata puede ejecutarse mientras la nueva revisión continúa descargándose. Un bloqueo de seguridad se muestra en el informe y no
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

## Recuperación tras reiniciar

Los trabajos que estaban activos vuelven a la cola automáticamente al arrancar.
En Torrent se comprueban los hashes antes de enviar. En Calibre, un envío en curso
queda sin confirmar y se consulta su resultado antes de continuar, sin repetir
la subida. Los trabajos que esperaban descargas conservan sus dependencias.

Las limpiezas comprueban los borrados que ya se habían solicitado y continúan con
los elementos que todavía no se habían enviado. No repiten automáticamente un
borrado incierto ni eliminan la revisión que debe conservarse. Si no se puede
verificar una operación, sigue requiriendo revisión.

Los trabajos pausados y cancelados permanecen así. Los que ya quedaron pausados
por un reinicio con una versión anterior deben reanudarse manualmente una vez.

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
El trabajo debe estar terminado o cancelado y sin operaciones en curso; también se pueden reintentar fallos de envío mientras espera otras descargas. Para
continuar un trabajo pausado, utiliza **Reanudar**.

El diálogo resume los envíos, verificaciones, esperas y limpiezas recuperables.
Los elementos sin nada pendiente o con problemas se excluyen y se indica el
motivo. Los cambios de conexión o archivo requieren confirmación; los borrados
y los envíos inciertos requieren restaurar su conexión original.

Los reintentos se realizan dentro del mismo trabajo y conservan el historial de intentos, la revisión y las opciones originales. Los enlaces a trabajos de reintento antiguos siguen disponibles.

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

## Eliminar el historial de trabajos

En **Descargas > Trabajos** y **Calibre > Trabajos** puedes seleccionar filas
(o la página actual) y pulsar **Eliminar trabajos**. La previsualización indica
cuáles se pueden eliminar y el motivo concreto de los bloqueos, con un enlace
al trabajo dependiente cuando tienes permiso para consultarlo. Solo se eliminan
los trabajos permitidos; el servidor comprueba otra vez sus condiciones al confirmar.

Se pueden eliminar trabajos **terminados** y **cancelados**, incluidos los que
terminaron con errores, siempre que no tengan envíos, descargas, limpiezas o
resultados inciertos pendientes. Los pausados deben cancelarse primero.
Un trabajo Torrent enlazado desde Calibre se conserva hasta eliminar el trabajo
de Calibre que lo referencia, aunque este último haya terminado. Esta protección
incluye los reintentos históricos de la descarga enlazada. Las cadenas de
reintentos antiguas se eliminan desde sus hijos; si se seleccionan juntos, se
ordena el borrado automáticamente. Los ciclos automáticos abiertos protegen
también los reintentos enlazados indirectamente.

Eliminar trabajos normales requiere el permiso de gestión de trabajos de su
área. Para limpiezas se exige ser su propietario y disponer de los permisos de
consulta y eliminación de Descargas o Calibre. El borrado elimina el trabajo,
sus elementos y su historial de intentos. Conserva torrents, archivos, registros
de Descargas, libros de Calibre, Eventos y resultados de tareas programadas.
Se mantienen las marcas mínimas necesarias para impedir que una petición antigua
vuelva a enviar o borrar al desaparecer el historial del trabajo.

## Esperas y bloqueos

Los detalles de los trabajos distinguen **Esperando automáticamente** de
**Requiere tu intervención**. El motivo explica qué impide continuar. Si se conoce
el trabajo relacionado, se ofrece su enlace, sujeto a los permisos de consulta.

Una descarga pendiente o una verificación que el trabajador sigue comprobando no
requiere un reintento manual. Un trabajo pausado, una descarga pendiente de revisión,
un fallo o un borrado sin confirmar que no se está verificando sí requieren revisión.
El estado de espera no autoriza nuevos envíos ni borrados ni evita las comprobaciones
de identidad y archivos compartidos. Una limpieza detenida puede necesitar un nuevo
intento aunque el trabajo que la bloqueaba ya haya terminado.

En las tablas de elementos de los trabajos de Descargas, Calibre y sus limpiezas, **Autor** se
obtiene del Catálogo mediante el EPL ID y requiere permiso de lectura del Catálogo.
La columna aparece después de Libro (Archivo en Calibre) y permite ordenar. El
filtro Autor admite varios términos, negación y borrado rápido, y se combina con
los demás filtros antes de paginar. Al pulsar el autor de una fila se aplica ese
autor como filtro positivo, conservando los demás filtros y volviendo a la primera
página. Si el libro ya no está en el Catálogo, el autor
se muestra como «—»; los trabajos y archivos originales no se modifican.
