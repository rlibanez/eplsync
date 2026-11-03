# Limpieza de revisiones y biblioteca

[Volver a las guías](README.md)

El botón **Limpiar revisiones antiguas** está disponible en **Descargas > Estado**. En **Calibre > Biblioteca**, utiliza **Limpiar biblioteca**, según los permisos de la cuenta. La limpieza es manual y crea un trabajo independiente, consultable en **Descargas > Trabajos** o **Calibre > Trabajos**, identificado con el tipo **Limpieza**. Comparte filtros, ordenación y paginación con los demás trabajos; su enlace abre el detalle de limpieza.

## Asistente

1. Elige la acción y el ámbito: todos los registros o los seleccionados en la tabla. Se consultan los registros de la última sincronización y se comprueban en el cliente; sincroniza primero para incorporar libros nuevos.
2. Revisa la previsualización: un registro por fila, con el EPL ID, las revisiones que se eliminan y conservan, sus identificadores y los motivos de exclusión. Puedes marcar o desmarcar los candidatos disponibles.
3. Confirma la selección. Borrar archivos torrent o entradas completas de Calibre exige una confirmación adicional.

Las previsualizaciones no ejecutan borrados. La limpieza de revisiones de Descargas admite hasta 1000 revisiones por previsualización. **Limpiar biblioteca** de Calibre no tiene un límite total de entradas: se prepara en segundo plano, con datos persistidos y paginación desde el servidor. Una preparación activa se mantiene vigente; una vez lista, caduca a los 30 minutos sin actividad de preparación.

## Descargas

La acción sobre qBittorrent y el registro local se eligen por separado:

- **Conservar torrent y archivos.**
- **Borrar solo el torrent; conservar archivos.**
- **Borrar torrent y archivos.**

La casilla **Borrar también el registro de EPL Sync** está desmarcada por defecto. Permite borrar únicamente el registro si se conservan torrent y archivos. Si se solicita también un borrado en qBittorrent, el registro se elimina solo después de confirmar la ausencia del torrent; una orden enviada o un fallo de conexión no bastan. La elección queda guardada en el trabajo y se conserva al comprobarlo o reintentarlo. Los trabajos anteriores conservan su política original.

Por defecto, solo se elimina una revisión si existe otra estrictamente superior del mismo EPL ID, registrada como descargada, todavía completa en el cliente y con un EPUB accesible desde EPL Sync. La última revisión del Catálogo, por sí sola, no basta.

La opción **Permitir la limpieza aunque la revisión superior no esté descargada** está desmarcada por defecto. Al marcarla se acepta que puede desaparecer la única copia descargada: sigue siendo obligatorio que exista el torrent de una revisión superior del mismo libro en el cliente. La opción queda guardada en el trabajo y se muestra en la confirmación y en su detalle. No se aplica a Calibre.

Si una Actualización tiene una Limpieza relacionada pendiente, utiliza los controles de esa Limpieza en lugar de crear otra para las mismas revisiones. Las solicitudes antiguas que aún conservan el seguimiento anterior pueden resolverse mediante una limpieza manual compatible, con la misma política y revisión de sustitución. La ausencia confirmada del torrent antiguo resuelve la solicitud original sin repetir el borrado. Otros borrados pendientes, políticas incompatibles y operaciones sin confirmar siguen protegidos.

La eliminación de archivos mantiene las comprobaciones de rutas compartidas, rutas no verificables y torrents compartidos con otros libros. Un torrent sin metadatos y sin datos puede retirarse sin solicitar el borrado de archivos inexistentes.

## Calibre

La limpieza elimina la **entrada antigua completa**, incluyendo todos sus formatos, metadatos y portada. Se conserva la revisión superior verificada. No se ofrece borrar únicamente el EPUB.

Las previsualizaciones antiguas de borrado exclusivo de EPUB no se pueden confirmar. Los elementos no iniciados de esos trabajos quedan bloqueados y deben cancelarse; no se convierten en borrados completos. Si había un borrado sin confirmar, se permite comprobar únicamente el resultado de la operación original, sin ampliar ni repetir el borrado.

En **Limpiar biblioteca** puedes marcar **Limpiar revisiones antiguas**, **Limpiar duplicados** o ambas opciones. Ambas opciones están marcadas por defecto. Los duplicados se identifican por el mismo EPL ID y revisión, no por título ni por igualdad del contenido del EPUB; las entradas sin identificadores completos quedan fuera de esta detección.

La previsualización distingue **Revisión antigua** y **Duplicado**, muestra los formatos que se eliminarán y la copia que se conserva. Si se combinan las dos opciones, se conserva una copia de la revisión más reciente y cada entrada se elimina como máximo una vez. Si solo se limpian duplicados, se conserva una copia de cada revisión y no se eliminan las revisiones antiguas completas.

Por defecto se conserva el Calibre ID más bajo de cada grupo pertinente; puedes elegir otra copia en **Copias que se conservan**. Al cambiarla se recalculan los candidatos del libro afectado; las selecciones de los demás libros se conservan. Si los duplicados tienen formatos distintos, es obligatorio elegir explícitamente cuál conservar, incluso si es la ya seleccionada: se eliminan todos los formatos de las otras entradas, sin fusionarlos.

Antes de cada borrado se consulta Calibre para verificar los identificadores de ambas entradas y la existencia de un EPUB en la copia que se conserva. La limpieza de biblioteca conserva además una instantánea de sus metadatos y formatos: si cualquiera cambia después de previsualizar, se bloquea el borrado. Las limpiezas anteriores mantienen su regla de revisión estrictamente superior. La elección queda persistida para la recuperación tras reiniciar y los borrados inciertos solo se verifican, sin repetir la orden.

## Protección y seguimiento

Al arrancar, EPL Sync recupera las solicitudes de limpieza nunca enviadas que hayan quedado pendientes o bloqueadas bajo un trabajo cancelado, y las marca como canceladas. Dejan de impedir una nueva previsualización o la eliminación del historial del trabajo. Esta recuperación no envía borrados ni descarta solicitudes ya enviadas cuyo resultado siga sin confirmar.

- Los libros sin EPL ID o sin revisión utilizable no son candidatos.
- Los libros con envíos, limpiezas incompatibles o resultados sin confirmar pendientes quedan excluidos. También se respetan las dependencias de los trabajos de Calibre.
- Las comprobaciones se repiten antes de actuar. Cambiar la conexión o biblioteca invalida la previsualización y bloquea las operaciones pendientes.
- Los trabajos y su detalle se guardan en la base de datos y pertenecen a la cuenta que los creó. Se comprueban de nuevo sus permisos al ejecutar cada elemento.
- Cancelar impide iniciar los elementos pendientes; no revierte ni interrumpe un borrado ya iniciado.
- Tras un reinicio, el trabajo en ejecución queda interrumpido. Se pueden continuar los elementos no iniciados. Los borrados posiblemente enviados quedan **Sin confirmar**.
- Si un trabajo interrumpido conserva elementos sin iniciar, primero continúa o cancela esos pendientes. **Comprobar borrados sin confirmar** solo consulta el resultado. Nunca repite una orden de borrado. Si un torrent sigue presente, **Reintentar borrados sin confirmar** permite solicitar de nuevo su eliminación desde el trabajo original, pulsando directamente ese botón, que autoriza el reenvío con la política de borrado original. Reutiliza la solicitud anterior, repite las protecciones y no reenvía borrados ya completados. Si vuelve a perderse la conexión, permanece sin confirmar y no se repite automáticamente. Calibre conserva la comprobación sin reenvío. Mientras quede sin confirmar, no se admite otra limpieza del mismo libro.
- Cada trabajo registra en Eventos solo fallos o resultados parciales, agrupados y sin repetir un mismo bloqueo al comprobarlo otra vez. El detalle individual permanece en el trabajo. El inicio y el resultado pueden generar avisos flotantes según los ajustes de notificaciones.

Los trabajos terminados se conservan durante 90 días; los que contienen borrados sin confirmar no se eliminan automáticamente. No se envían avisos de Telegram por estas limpiezas manuales.

### Preparación de bibliotecas grandes

La preparación consulta formatos y comprueba candidatos por lotes de hasta 20 elementos y 30 segundos por ciclo; cada consulta en curso conserva su tiempo de espera de conexión. Las escrituras de candidatos y copias se dividen en lotes de 500. Se prepara un libro cada vez y se conservan los puntos de avance en SQLite. Un reinicio recupera la preparación pendiente sin iniciar borrados.

La previsualización muestra 20 candidatos por página, cinco grupos de copias que conservar y hasta 20 copias por consulta del selector. La ordenación y la selección se resuelven en el servidor. **Seleccionar disponibles** y **Quitar selección** afectan a toda la previsualización, no solo a la página visible. Cambiar de página conserva la selección.

Puedes cancelar y cerrar mientras se prepara. La confirmación comprueba la versión de la previsualización para impedir que se confirme una selección anterior a un cambio de copia. El borrado sigue procesándose por lotes de hasta 20 elementos y 30 segundos, con las comprobaciones de identidad, formatos, permisos, biblioteca y trabajos incompatibles antes de cada operación. Una operación incierta se comprueba sin repetir el borrado.

### Reintentar elementos fallidos

Un trabajo de limpieza terminado con elementos **Fallidos** ofrece **Reintentar
fallidos (n)** en su barra de acciones, tanto para Descargas como para Calibre.
Se reutiliza el trabajo original y se conservan usuario, fecha y motivo anterior en
**Historial de reintentos**, paginado desde el servidor. Las peticiones repetidas
reutilizan la misma solicitud y no crean nuevos trabajos ni nuevos intentos.

El historial también recoge las comprobaciones de borrados sin confirmar, sus
reintentos explícitos y la continuación de pendientes. Cada actuación conserva el
detalle anterior. Los reintentos antiguos sin detalle conservado se identifican
como tales; no se reconstruyen datos que ya no existen.

El reintento vuelve a comprobar permisos, destino original, identidad, revisión
conservada y protecciones de borrado. No modifica elementos eliminados, bloqueados,
cancelados ni sin confirmar. Si un fallo conserva constancia de un borrado ya enviado,
se comprueba su resultado sin reenviarlo. Los borrados sin confirmar mantienen sus
acciones específicas de comprobación y reintento explícito. Un trabajo cancelado no
se reactiva con esta acción.

### Limpieza relacionada con una Actualización

Al solicitar borrar revisiones anteriores durante una Actualización, EPL Sync crea
un trabajo de **Limpieza** relacionado, visible por separado en Descargas > Trabajos
y en el Home. Los informes enlazan en ambos sentidos, según los permisos y el
propietario. La Actualización termina cuando finalizan sus envíos y el seguimiento
de descargas; no espera a que termine la Limpieza.

La Limpieza utiliza las solicitudes originales y su historial, sin crear otras
órdenes para los mismos torrents. «Inmediatamente» espera la aceptación de todos los
hashes de sustitución del libro; «Después de descargar» espera además su descarga
completa. Los libros todavía pendientes no impiden limpiar los que ya están listos.
Las comprobaciones de dependencia se aplazan conforme al intervalo de limpieza de
revisiones configurado en Tareas. Se conservan las protecciones de Calibre, identidad,
rutas y archivos compartidos, y los reintentos del trabajo de Limpieza.

Cancelar la Limpieza conserva la Actualización y cancela solo sus borrados no
iniciados. Cancelar la Actualización cancela también los borrados no iniciados de su
Limpieza relacionada. Los borrados ya solicitados quedan sin confirmar y pueden
comprobarse, sin repetir automáticamente la orden.

Si la Actualización está cancelada, **Comprobar borrados sin confirmar** sigue
permitiendo consultar el resultado. Si el torrent antiguo permanece,
**Reintentar borrados sin confirmar** autoriza un nuevo intento dentro de la
Limpieza original. Solo afecta a solicitudes previamente enviadas y vuelve a
comprobar la aceptación de los hashes de sustitución, su presencia en el cliente,
la política de descarga, los permisos y las protecciones de Calibre y archivos
compartidos. No reactiva la Actualización ni repite borrados ya confirmados.

Las solicitudes de trabajos anteriores se incorporan en segundo plano, conservando
sus identificadores, políticas, resultados y enlaces al trabajo de origen. Solo se
incorporan con un propietario conservado y el cliente original configurado; los demás
trabajos mantienen su seguimiento anterior. Se debe eliminar primero el historial de
la Limpieza y luego el de la Actualización. El historial relacionado se conserva hasta
su eliminación explícita para no perder la procedencia de las solicitudes.

### Torrents ausentes y registros conservados

Si no se solicita borrar el registro de EPL Sync, las revisiones antiguas siguen en
el historial de Descargas después de eliminarse de qBittorrent. La previsualización
contrasta los hashes con una consulta actual al cliente y no ofrece esos registros
como torrents disponibles para borrar. El filtrado precede al límite de la
previsualización; el estado guardado no sustituye a la consulta actual.

Si un torrent desaparece después de previsualizar y antes de ejecutar una orden
nueva, se muestra como **Omitido** y no como otra eliminación. Los borrados ya
enviados siguen conservando su comprobación de ausencia. Si se elige borrar también
el registro, un torrent ya ausente permite eliminar únicamente ese registro y se
informa de ello. No se vuelven a borrar torrents ni archivos por conservar el
historial local.
