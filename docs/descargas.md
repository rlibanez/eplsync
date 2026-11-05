# Estado e historial de descargas

[Guías](README.md) · [Inicio](../README.md)

## Estado de las descargas

**Descargas → Estado** muestra los registros de EPL Sync. Un libro puede tener
varias revisiones o hashes. Utiliza los filtros y los controles **Ordenar** y
**Columnas** para adaptar la tabla.

El filtro **EPL ID** admite varios identificadores, añadidos con Intro o pegados separados por comas, punto y coma o espacios. Incluye **= / ≠** y una X para vaciarlo; se conserva al paginar y al seleccionar todos los resultados.

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

Al confirmar **Eliminar** desde Estado de las descargas se crea un trabajo de tipo
**Limpieza** en **Descargas → Trabajos**, y se abre su detalle. El trabajo conserva
por separado la acción sobre el torrent y la opción de borrar el registro de EPL Sync.
El registro solo se elimina tras confirmar la ausencia del torrent, salvo si se eligió
borrar únicamente el registro local.

El detalle muestra los elementos pendientes, bloqueados, fallidos y sin confirmar.
**Continuar** vuelve a comprobar los bloqueados o fallidos tras resolver su causa;
**Comprobar borrados sin confirmar** solo consulta el cliente; **Reintentar borrados
sin confirmar** autoriza expresamente otra orden de borrado, con las mismas
protecciones. Un reinicio o una consulta no repite una orden incierta.

Los EPUB necesarios para Calibre (incluidos los trabajos pausados), los envíos torrent
activos, las revisiones de sustitución y las limpiezas incompatibles siguen protegidos.
Un borrado automático compatible de una actualización queda resuelto al confirmar la
misma ausencia, sin enviar otra orden. Las solicitudes manuales pendientes anteriores
se incorporan a los trabajos de su propietario al consultar la lista, sin ejecutarlas de nuevo.

## Sincronizar con el cliente

- **Sincronizar con el cliente:** actualiza los registros según el estado de qBittorrent.

La sincronización puede solicitarse mientras hay trabajos de envío o limpieza
activos. Espera hasta 30 segundos para obtener turno al terminar la operación que
esté accediendo al cliente; los nuevos envíos y comprobaciones ceden ese turno.
La consulta se realiza después de obtener el acceso, y se mantiene la exclusión
hasta aplicar el resultado y las limpiezas que correspondan. Así no se aplican
instantáneas anteriores a un envío o borrado. Durante ese turno, las operaciones
que modifican el cliente esperan o se aplazan; los trabajos conservan su estado.
Si la operación actual tarda más de 30 segundos, la sincronización informa de la
espera agotada sin iniciarse ni repetir envíos o borrados.

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

Las vistas de actualizaciones conservan el resultado y la fecha de la última
búsqueda manual por usuario en este navegador. Recargar o volver a abrir la
vista no consulta al servidor ni recalcula la comparación. La primera vez se
muestra la tabla vacía. **Buscar actualizaciones** renueva el resultado; si
falla, se conserva la búsqueda anterior. Borrar los datos del sitio elimina
estas búsquedas guardadas; no se comparten entre dispositivos.

## Limpieza manual de revisiones antiguas

Consulta [Limpiar revisiones antiguas](limpieza-revisiones.md) para preparar una limpieza con previsualización, selección y seguimiento por trabajo.

La limpieza manual permite marcar «Permitir la limpieza aunque la revisión superior no esté descargada» (desmarcado por defecto). La opción queda guardada en el trabajo y advierte de que se puede perder la única copia descargada. Siempre se conserva el torrent de la revisión superior y se comprueban las identidades, las rutas compartidas y los trabajos incompatibles antes de borrar.

Si un trabajo de actualización ya espera el mismo borrado automático, con la misma política y revisión de sustitución, la limpieza manual puede adelantarlo. Al confirmar la ausencia del torrent antiguo, también se resuelve la solicitud del trabajo original; no se repite el borrado. Los envíos activos, las eliminaciones sin confirmar y las dependencias de Calibre siguen impidiendo la limpieza.

## Reintentar elementos de un trabajo

**Reintentar fallidos** y **Reintentar seleccionados** reabren el mismo trabajo;
no crean otro trabajo ni aumentan su número de elementos. Solo se ponen de nuevo
en cola los elementos recuperables elegidos. Los ya aceptados no se vuelven a
enviar; una limpieza cancelada puede recuperarse sin reenviar los torrents.

El informe muestra los resultados actuales. El **Historial de reintentos**
conserva la fecha, el usuario y los estados y detalles anteriores y posteriores
de cada intento, incluida la limpieza. Sus listados están paginados.

La previsualización y la confirmación se comprueban de nuevo antes de reabrir el
trabajo. Una confirmación repetida no programa otra ejecución. Para reintentar
hay que esperar a que terminen las operaciones en curso y conservar la conexión
original. Tras un reinicio se aplican las mismas comprobaciones de recuperación
que al resto del trabajo.

Los enlaces entre trabajos de reintento creados con versiones anteriores se
conservan. Los elementos ya transferidos a uno de esos trabajos quedan excluidos
en el original; hay que continuar desde el trabajo enlazado.

Si falla el envío de una revisión de sustitución, el trabajo de actualización
queda pausado con un motivo explícito, conservando pendientes los borrados.
**Reintentar fallidos** recupera el envío dentro del mismo trabajo, con historial,
sin cancelar ni duplicar las solicitudes de limpieza. Las automatizaciones pueden
hacer ese reintento dentro de su presupuesto configurado. La revisión antigua no
se elimina hasta cumplir la condición de sustitución correspondiente.

Los trabajos manuales con propietario identificado vuelven a comprobar su cuenta
y permisos antes de nuevos envíos y borrados, igual que los reintentos y las tareas
programadas. Desactivar la cuenta, eliminarla o retirar permisos impide nuevas
operaciones; una petición ya iniciada puede terminar y se conserva su resultado.
La interfaz sigue actualizando los trabajos cancelados mientras queden envíos en
curso, y actualiza la disponibilidad de los reintentos cuando terminan.

En **Descargas → Trabajos**, la selección permite **Continuar**, **Pausar** o
**Cancelar trabajo** cuando hay trabajos compatibles. El contador de cada botón
indica cuántos recibirán la acción, incluidas selecciones de otras páginas. Antes
de actuar se comprueba su estado actual; los trabajos que ya no sean compatibles
se omiten, y un fallo no impide procesar los demás. La cancelación requiere
confirmación y conserva las reglas de propagación a Calibre. Las limpiezas no
admiten pausa manual. **Eliminar trabajos** mantiene su previsualización y las
protecciones de operaciones pendientes y dependencias; un trabajo activo debe
cancelarse y resolver sus operaciones pendientes antes de eliminar su historial.

### Progreso al preparar envíos grandes

Al pulsar **Crear trabajo**, el asistente muestra la espera de turno, la consulta
de la selección, la preparación de elementos y el guardado final. Cuando conoce
el total, muestra una barra y los contadores de libros procesados y elementos
preparados; un libro puede generar varios elementos si tiene varios hashes.
Estos contadores corresponden a la creación del trabajo. Los envíos al cliente
y la descarga de los archivos se siguen después en el propio trabajo.

La selección se fija con una única consulta ordenada y una lectura secuencial consistente,
sin repetir consultas con desplazamientos crecientes. Se procesa desde un archivo
privado temporal. Los elementos se guardan en transacciones de hasta 100 libros,
liberando el escritor de SQLite entre lotes. El índice temporal de hashes no hace
un commit por cada hash. El trabajo permanece inactivo y oculto de las listas
hasta completar su preparación; entonces se activa una sola vez. Se conservan
los filtros, exclusiones, ordenación, opciones y deduplicación de hashes.

Durante la captura y preparación del envío general del Catálogo se puede pulsar
Cancelar. Se espera la confirmación del servidor: se eliminan únicamente los
trabajos y elementos provisionales de esa operación, sin enviar ni borrar torrents.
Al comenzar la activación final, la cancelación deja de estar disponible; después
se utilizan los controles del trabajo. Cerrar una conexión no cancela por sí solo.

Si falla la preparación después de guardar algún lote, el trabajo y sus elementos
pendientes quedan cancelados sin enviar torrents. El aviso enlaza a ese trabajo
real si tienes permiso para verlo. Si no llegó a guardarse un trabajo, no se
muestra un enlace ficticio. Una referencia de incidencia identifica el error en
el log, no un trabajo. Tras un reinicio, las preparaciones incompletas también se
cancelan de forma segura; hay que iniciar una nueva selección. Los trabajos ya
activados conservan su recuperación habitual.

El log registra el inicio de la petición antes de esperar el turno, los cambios
de fase y finales en INFO y el resultado con el ID del
trabajo. Los fallos conservan el tipo de causa técnica sin registrar credenciales.
SNAPSHOT y PREPARING no generan logs parciales, pero mantienen el progreso del frontend.
La consulta del progreso no solicita datos al cliente torrent ni escribe en SQLite.
Solo puede consultarla el usuario que inició el envío, conservando su permiso de
Envío torrent.

Si se pierde la respuesta HTTP, el asistente consulta el resultado de la misma
petición, sin volver a enviarla. Repetir su identificador dentro de la misma
instancia devuelve el resultado conservado, sin crear otro trabajo. Este
seguimiento es temporal y se conserva en memoria durante una hora; no sustituye
al trabajo persistente ni añade tablas. Si el servidor se reinicia o no se puede
recuperar el resultado, se indica que hay que comprobar los trabajos antes de
iniciar otro envío de la misma selección.

## Inventario de archivos y extensiones

EPL Sync conserva el inventario de cada registro en `torrent_download_files`:
índice del cliente, ruta relativa (incluidas las subcarpetas), tamaño esperado y
extensión normalizada. No guarda los archivos ni rutas absolutas del servidor.
El registro de Descargas conserva el estado, fecha y último error de comprobación.
Eliminar el registro elimina también su inventario, sin borrar archivos del cliente.

Un trabajador consulta los inventarios pendientes en segundo plano, hasta cinco
registros por ciclo y con un segundo de presupuesto entre consultas. No mantiene
una transacción SQLite durante la red. Los envíos masivos tienen prioridad: mientras
están activos o esperando un reintento automático, el inventario cede sin consultar
al cliente ni esperar un bloqueo.
Cuando recupera el turno, comprueba primero los torrents descargados. Sus lecturas
son compartidas con los envíos y siguen excluidas de los borrados.
Los inventarios comprobados no se consultan continuamente: las comprobaciones
reales de archivos actualizan también el inventario. Los torrents sin metadatos
se reintentan más tarde; una respuesta vacía o un fallo conserva el inventario
anterior. Los registros de torrents ausentes no se consultan en segundo plano.

Las esperas breves para obtener acceso al cliente torrent no generan mensajes de
log. Las esperas de un segundo o más se registran en DEBUG al terminar la espera,
con un máximo de un aviso por minuto para el coordinador. El aviso agrupa las
esperas prolongadas y cuenta los tiempos agotados e interrupciones desde el aviso
anterior. Esto no cambia los reintentos ni el límite de espera de 30 segundos; los
fallos de las operaciones siguen llegando a sus respectivos trabajadores.

En Estado de las descargas, la columna Extensiones (visible por defecto) y sus filtros leen
solo SQLite. Las extensiones seleccionadas se combinan con «o». La negación devuelve
el resto de registros, incluidos los inventarios sin comprobar y los errores que
no tengan identificada ninguna de las extensiones seleccionadas. Si un error conserva
EPUB en su inventario, sigue coincidiendo con EPUB. «Sin comprobar» permite distinguir
los inventarios desconocidos. Conocer un archivo no garantiza que haya terminado su
descarga: su disponibilidad se comprueba antes de enviarlo.

Se admiten para el envío EPUB, AZW3, MOBI y PDF. Las carpetas se recorren mediante
los archivos enumerados por el cliente; RAR, ZIP y 7Z quedan sin extracción.

La columna **Extensiones** está visible por defecto y permite ordenar por el
listado alfabético de extensiones de cada torrent. Al actualizar se muestra una
vez también para preferencias anteriores; después se respeta si se oculta.
Pulsar una extensión aplica su filtro. El filtro admite **Sin comprobar**, combinable
con extensiones conocidas; también permite excluir esa selección.

El filtro **Extensiones** incluye también **Sin comprobar**, **Error de comprobación**
y **Otro**; no hay un filtro separado de Inventario. «Otro» agrupa archivos con
extensiones distintas de EPUB, AZW3, MOBI, PDF, RAR, ZIP, 7Z y CBR, incluidos los archivos
sin extensión. Las extensiones concretas detectadas siguen disponibles.
Las opciones seleccionadas se combinan como alternativas. «Sin comprobar» no incluye
comprobaciones fallidas. Si un fallo conserva un inventario anterior, sus últimas
extensiones conocidas se mantienen visibles junto al aviso de error.

### Archivos del torrent en la ficha del libro

En **Historial de descargas** de la ficha del Catálogo se muestran las extensiones
por registro, con columna visible por defecto y ordenable. La acción **Ver archivos**
abre una tabla paginada con los nombres/rutas relativas, extensiones y tamaños del
inventario local. Cada torrent conserva su propio listado, aunque comparta EPL ID
o revisión con otro torrent. No se consulta qBittorrent ni se abre ningún archivo.

Ver estos metadatos requiere **Catálogo: lectura** e **Historial de descargas: lectura**
(`CATALOG_READ` y `BOOK_HISTORY_READ`). No concede permiso para descargar archivos;
las operaciones de lectura del contenido mantienen sus protecciones independientes.
El popup distingue inventarios sin comprobar y fallidos, conserva el último
inventario conocido y advierte de que los archivos pueden no estar completamente
descargados. Las consultas y la paginación no modifican el inventario ni los torrents.

Ante un fallo de comunicación con el cliente, el trabajador de inventario detiene
el lote y espera un minuto antes de continuar: no prueba otros torrents cada
segundo contra un servidor que no responde. La espera afecta solo a ese destino;
un cambio de cliente permite comprobar el nuevo destino inmediatamente. Los fallos
específicos de un archivo mantienen su reintento individual y no detienen el resto.
El último inventario guardado se conserva durante la desconexión.

Los envíos concurrentes de un mismo trabajo comparten durante cinco segundos el
fallo transitorio al cargar el índice de hashes, evitando consultas idénticas en
ráfaga. Esto no autoriza ningún alta: antes de enviar se sigue exigiendo un índice
comprobado. Los avisos DEBUG de comunicación con qBittorrent se agrupan, con un
máximo de uno por minuto para cada configuración activa y un contador de fallos
acumulados. Los errores se siguen propagando a los trabajos.

## Confirmación y prioridad de envíos masivos

«Aceptado» requiere comprobar la presencia del torrent en el cliente. Las
comprobaciones simultáneas se agrupan y utilizan los identificadores devueltos por
qBittorrent cuando están disponibles. No necesitan interpretar el progreso de
descarga. La resolución alternativa de hashes híbridos comparte una consulta
completa limitada a una por cada 30 segundos, en lugar de repetirla por elemento.

Una incorporación todavía no visible queda pendiente de confirmación en el mismo
trabajo. Los demás elementos pueden avanzar. El plazo para reconciliar el envío y
la fecha de reintento se conservan en SQLite; reiniciar no autoriza a repetir un
POST incierto sin comprobar antes el hash. Los reintentos individuales tienen un
límite y conservan la protección de las revisiones de sustitución.

Los fallos de comunicación aislados se aplazan por elemento. Ante fallos
consecutivos se mantiene la espera global y la pausa por agotamiento de reintentos,
para no saturar un cliente caído. Los reintentos automáticos del mismo trabajo
conservan su índice y categorías; un cambio de destino o trabajo no comparte ese
contexto. Cancelar conserva lo ya incorporado y no reactiva elementos pendientes.

La cabecera del informe muestra por separado el progreso de Envío, Descargas y,
si hay trabajos relacionados y permisos para consultarlos, Calibre. Envío cuenta
los elementos procesados o cancelados; Calibre cuenta los enviados, existentes,
fallidos, cancelados u omitidos, manteniendo aparte los pendientes y sin confirmar.
Llegar al 100 % de procesamiento no implica que todos hayan terminado con éxito.
Descargas cuenta únicamente los torrents descargados sobre el total de elementos
seleccionados, igual que Envío. Su denominador no aumenta conforme se incorporan
torrents. Los omitidos, fallidos y cancelados no cuentan como descargados; un
trabajo terminado con incidencias puede quedarse por debajo del 100 % de descarga.

El apartado Descarga muestra también el estado y los torrents con archivos
comprobados en el inventario local, sin consultar al cliente al abrir el informe.
Este contador no condiciona la finalización del trabajo: puede haber descargas
terminadas cuyo inventario todavía esté pendiente o tenga un error de comprobación.

El seguimiento automático comparte el acceso al cliente con los envíos: puede
consultar el estado mientras se incorporan torrents. Mantiene la exclusión con
borrados y sincronizaciones completas, sin esperar en la cola por delante de ellos.
Si un hash está enviándose o se envía durante una consulta, esa instantánea no se
aplica a su registro; lo revisará el siguiente ciclo. Los demás hashes sí se
actualizan. Esta protección solo conserva información durante la consulta y no
crea un historial ni una caché permanente por torrent.

En el trabajo de limpieza de revisiones torrent, «Priorizar limpieza» adelanta
una limpieza activa y pendiente para su próximo turno acotado. La solicitud se
conserva tras reiniciar y se consume al tomar el turno; después vuelve al orden
normal, sin monopolizar la cola. Solo el propietario con los permisos de la
operación puede solicitarla. No reanuda trabajos pausados ni reintenta elementos
fallidos, bloqueados o sin confirmar: para eso siguen existiendo sus controles.

También sin prioridad manual, las limpiezas torrent esperan en la cola justa de
acceso exclusivo al cliente, hasta 30 segundos por intento, en lugar de fallar
inmediatamente si una lectura está en curso. Los envíos ya iniciados terminan;
las observaciones y el inventario ceden mientras espera ese turno. Se conservan
los lotes acotados y las verificaciones de revisiones, permisos, dependencias y
borrados inciertos, sin mantener una conexión SQL durante la espera de acceso.
