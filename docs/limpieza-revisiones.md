# Limpiar revisiones antiguas

[Volver a las guías](README.md)

El botón **Limpiar revisiones antiguas** está disponible en **Descargas > Estado** y **Calibre > Biblioteca**, según los permisos de la cuenta. La limpieza es manual y crea un trabajo independiente, consultable en **Descargas > Trabajos** o **Calibre > Trabajos**, identificado con el tipo **Limpieza**. Comparte filtros, ordenación y paginación con los demás trabajos; su enlace abre el detalle de limpieza.

## Asistente

1. Elige la acción y el ámbito: todos los registros o los seleccionados en la tabla. Se consultan los registros de la última sincronización y se comprueban en el cliente; sincroniza primero para incorporar libros nuevos.
2. Revisa la previsualización: un registro por fila, con el EPL ID, las revisiones que se eliminan y conservan, sus identificadores y los motivos de exclusión. Puedes marcar o desmarcar los candidatos disponibles.
3. Confirma la selección. Borrar archivos torrent o entradas completas de Calibre exige una confirmación adicional.

Las previsualizaciones caducan a los 30 minutos, admiten hasta 1000 revisiones y no ejecutan borrados. Si hay más, selecciona un grupo de registros y prepara su limpieza.

## Descargas

La acción sobre qBittorrent y el registro local se eligen por separado:

- **Conservar torrent y archivos.**
- **Borrar solo el torrent; conservar archivos.**
- **Borrar torrent y archivos.**

La casilla **Borrar también el registro de EPL Sync** está desmarcada por defecto. Permite borrar únicamente el registro si se conservan torrent y archivos. Si se solicita también un borrado en qBittorrent, el registro se elimina solo después de confirmar la ausencia del torrent; una orden enviada o un fallo de conexión no bastan. La elección queda guardada en el trabajo y se conserva al comprobarlo o reintentarlo. Los trabajos anteriores conservan su política original.

Por defecto, solo se elimina una revisión si existe otra estrictamente superior del mismo EPL ID, registrada como descargada, todavía completa en el cliente y con un EPUB accesible desde EPL Sync. La última revisión del Catálogo, por sí sola, no basta.

La opción **Permitir la limpieza aunque la revisión superior no esté descargada** está desmarcada por defecto. Al marcarla se acepta que puede desaparecer la única copia descargada: sigue siendo obligatorio que exista el torrent de una revisión superior del mismo libro en el cliente. La opción queda guardada en el trabajo y se muestra en la confirmación y en su detalle. No se aplica a Calibre.

Si un trabajo de actualización espera exactamente el mismo borrado, con la misma política y revisión de sustitución, la limpieza manual puede adelantarlo. La ausencia confirmada del torrent antiguo resuelve también la solicitud automática y permite finalizar la fase de limpieza del trabajo original sin repetir el borrado. Otros borrados pendientes, políticas incompatibles y operaciones sin confirmar siguen bloqueando la limpieza.

La eliminación de archivos mantiene las comprobaciones de rutas compartidas, rutas no verificables y torrents compartidos con otros libros. Un torrent sin metadatos y sin datos puede retirarse sin solicitar el borrado de archivos inexistentes.

## Calibre

La limpieza elimina la **entrada antigua completa**, incluyendo todos sus formatos, metadatos y portada. Se conserva la revisión superior verificada. No se ofrece borrar únicamente el EPUB.

Las previsualizaciones antiguas de borrado exclusivo de EPUB no se pueden confirmar. Los elementos no iniciados de esos trabajos quedan bloqueados y deben cancelarse; no se convierten en borrados completos. Si había un borrado sin confirmar, se permite comprobar únicamente el resultado de la operación original, sin ampliar ni repetir el borrado.

Antes de cada borrado se consulta Calibre para verificar los identificadores y revisiones de ambas entradas y la existencia del EPUB de la revisión superior. Los duplicados de una misma revisión no se consideran revisiones antiguas.

## Protección y seguimiento

- Los libros sin EPL ID o sin revisión utilizable no son candidatos.
- Los libros con envíos, limpiezas incompatibles o resultados sin confirmar pendientes quedan excluidos. También se respetan las dependencias de los trabajos de Calibre.
- Las comprobaciones se repiten antes de actuar. Cambiar la conexión o biblioteca invalida la previsualización y bloquea las operaciones pendientes.
- Los trabajos y su detalle se guardan en la base de datos y pertenecen a la cuenta que los creó. Se comprueban de nuevo sus permisos al ejecutar cada elemento.
- Cancelar impide iniciar los elementos pendientes; no revierte ni interrumpe un borrado ya iniciado.
- Tras un reinicio, el trabajo en ejecución queda interrumpido. Se pueden continuar los elementos no iniciados. Los borrados posiblemente enviados quedan **Sin confirmar**.
- Si un trabajo interrumpido conserva elementos sin iniciar, primero continúa o cancela esos pendientes. **Comprobar borrados sin confirmar** solo consulta el resultado. Nunca repite una orden de borrado. Si un torrent sigue presente, **Reintentar borrados sin confirmar** permite solicitar de nuevo su eliminación desde el trabajo original, pulsando directamente ese botón, que autoriza el reenvío con la política de borrado original. Reutiliza la solicitud anterior, repite las protecciones y no reenvía borrados ya completados. Si vuelve a perderse la conexión, permanece sin confirmar y no se repite automáticamente. Calibre conserva la comprobación sin reenvío. Mientras quede sin confirmar, no se admite otra limpieza del mismo libro.
- Cada trabajo registra en Eventos solo fallos o resultados parciales, agrupados y sin repetir un mismo bloqueo al comprobarlo otra vez. El detalle individual permanece en el trabajo. El inicio y el resultado pueden generar avisos flotantes según los ajustes de notificaciones.

Los trabajos terminados se conservan durante 90 días; los que contienen borrados sin confirmar no se eliminan automáticamente. No se envían avisos de Telegram por estas limpiezas manuales.
