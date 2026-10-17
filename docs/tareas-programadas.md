# Tareas programadas

En **Ajustes → Tareas programadas**, los administradores pueden crear, editar,
activar y desactivar tareas. La configuración es compartida por toda la instalación.

## Sincronizaciones con cron

Las operaciones disponibles son **Sincronizar Torrent**, **Sincronizar Calibre**,
**Importar catálogo**, **Buscar actualizaciones del catálogo** y
**Buscar actualizaciones de Calibre**.
Cada tarea tiene un nombre y una expresión cron. Se asigna automáticamente
al usuario que la crea; editarla no cambia ese usuario. La tabla muestra la próxima y la última ejecución, su resultado, duración y los
problemas que haya encontrado.

Cron utiliza cinco campos: **minuto, hora, día del mes, mes y día de la
semana**. Algunos ejemplos:

| Cron | Programación |
| --- | --- |
| `*/15 * * * *` | Cada 15 minutos. |
| `0 5 * * *` | Todos los días a las 05:00. |
| `0 4 * * MON` | Los lunes a las 04:00. |

El botón **Info** junto al campo Cron muestra su descripción y las próximas
cinco ejecuciones al pasar el cursor o enfocar el botón.
La validación usa el mismo motor que ejecuta las tareas y bloquea el guardado
si la expresión es inválida o no tiene próximas ejecuciones.

Se usa la zona horaria de `TZ`, con `UTC` por defecto si no está configurada.
Se respetan los cambios de horario; cambiar `TZ` requiere reiniciar la aplicación.
Puedes preparar expresiones en [crontab.guru](https://crontab.guru/).
EPL Sync añade internamente los segundos a cero. Las expresiones existentes de
seis campos siguen siendo compatibles; consulta el
[formato con segundos](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-cron-expression). Tras un reinicio se calcula la próxima ejecución, sin
repetir todas las ejecuciones perdidas. Una ejecución interrumpida queda registrada.

El usuario debe estar activo, haber establecido su contraseña definitiva y tener
`TORRENT_SYNC`, `CALIBRE_SYNC` o `CATALOG_IMPORT`, según la operación.
Las búsquedas requieren además `CATALOG_READ`; la de Calibre también requiere
`CALIBRE_READ`. La disponibilidad de EPUB solo se consulta con
`DOWNLOADS_FILES_READ`. Los permisos se comprueban en
cada ejecución; no hace falta mantener una sesión abierta. Si la cuenta se elimina,
se desactiva o pierde el permiso, la tarea registra un fallo y no ejecuta la operación.
Su usuario creador se conserva también al editarla.

**Ejecutar ahora** inicia la tarea en segundo plano y actualiza esos mismos datos.
También funciona si está desactivada, sin activarla ni modificar su próxima ejecución cron.
Requiere ser administrador y tener los permisos de la operación; se vuelven a comprobar
los permisos del propietario al ejecutarla. Comparte las protecciones de concurrencia
con el cron. Los Eventos que genere esta ejecución tienen origen **Manual**; se
mantiene la política de registrar solo fallos o resultados parciales.

No se ejecutan simultáneamente dos tareas de la misma operación, ni una
sincronización y una búsqueda de actualizaciones de la misma integración. Las
sincronizaciones manuales conservan sus protecciones de concurrencia. Desactivar
una tarea evita nuevas ejecuciones, pero no interrumpe la que esté en marcha.
No se puede editar una tarea mientras se ejecuta. Se permiten hasta 200 tareas.

Importar catálogo actualiza desde la URL configurada en Ajustes, conservando
el historial y los libros ausentes. La URL se consulta en cada ejecución.

Las búsquedas de actualizaciones usan los mismos criterios que sus botones:
la de catálogo sincroniza torrent y compara las revisiones registradas con el
catálogo, usando los estados guardados en la configuración de actualizaciones.
La de Calibre sincroniza su biblioteca y compara sus revisiones con el catálogo.
No descargan ni envían libros. Tanto las búsquedas manuales como las programadas guardan el último resultado
completo en la tabla `update_search_results`, por usuario y tipo, con fecha,
origen, criterios y una lista de resultados en JSON. La migración conserva
los resultados válidos que antes se guardaban en `app_settings`.
Cada usuario consulta el mismo resultado desde cualquier navegador o dispositivo;
los resultados dejan de almacenarse en IndexedDB. Una búsqueda fallida
conserva el resultado anterior. Las vistas abiertas comprueban cada 30 segundos
si hay un resultado más reciente.

Las sincronizaciones no buscan ni envían revisiones nuevas. La sincronización
Torrent conserva el comportamiento de Estado de las descargas: las limpiezas
pendientes pueden ejecutarse según sus políticas y los permisos del usuario.
Calibre actualiza la copia local de la biblioteca y no modifica sus libros.

En **Eventos** solo se registran fallos y resultados parciales, con origen
**Programado**. El listado conserva siempre el resultado de la última ejecución.
El log usa DEBUG para el inicio y el fin correcto, WARN para fallos o resultados
parciales e INFO al recuperar tareas interrumpidas tras un reinicio.
`EPLSYNC_LOG_LEVEL=INFO` es el valor predeterminado; usa `DEBUG` para ver también
las ejecuciones correctas. Cambiarlo requiere reiniciar la aplicación.
Los mensajes habituales de sincronización, descarga del ZIP e importación
también usan DEBUG cuando proceden de una tarea programada; las operaciones
manuales mantienen INFO y los avisos de problemas mantienen WARN.
Los temporizadores y las operaciones se ejecutan en el backend, independientemente
de que el navegador esté abierto.

## Comprobaciones internas de trabajos

Son procesos distintos de las tareas con cron. Permiten editar su intervalo:

- **Limpieza de revisiones torrent**: revisa las solicitudes automáticas pendientes.
- **Descargas pendientes para Calibre**: comprueba si los EPUB que espera un trabajo
  ya están descargados y accesibles.

Ambos usan inicialmente `30s`, con un mínimo de `10s`. Los valores sin unidad
se interpretan en segundos. Puedes introducir duraciones como `30s`, `1m` o `5m`.
**Restablecer** recupera el valor de instalación para ese intervalo y elimina
su modificación guardada.
Los cambios se guardan en la base de datos y se aplican a las siguientes
comprobaciones sin reiniciar. No se consultan los servidores si no hay trabajo pendiente.

Los valores guardados tienen prioridad sobre
`EPLSYNC_TORRENT_CLEANUP_INTERVAL` y
`EPLSYNC_CALIBRE_TRANSFERS_DOWNLOAD_CHECK_INTERVAL`, que siguen siendo los valores
iniciales de instalación. La opción de instalación
`EPLSYNC_TORRENT_CLEANUP_ENABLED` conserva su función; no se expone como interruptor
en esta pantalla. Las políticas de borrado de cada actualización siguen determinando
qué revisiones se pueden eliminar.

Un reinicio normal de las bases de datos conserva las tareas y sus intervalos.
El reinicio completo de usuarios y ajustes los elimina. El reinicio se rechaza si
hay una tarea programada en ejecución.

## Actualizaciones automáticas

Las operaciones **Actualizar descargas** y **Actualizar biblioteca de Calibre**
son independientes. La primera comprueba los registros torrent y envía las nuevas
revisiones al cliente. La segunda comprueba los libros identificados en la biblioteca
actual de Calibre, reutiliza los EPUB disponibles o las descargas ya pendientes y
crea un trabajo de Calibre que espera a que estén descargados.
Las operaciones **Buscar actualizaciones** siguen sirviendo solo para consultar.

Al elegir una automatización, el asistente muestra cuatro pasos:

1. **Alcance**: actualización opcional del catálogo desde su URL, estados torrent
   y máximo de libros por ciclo (100 inicialmente; de 1 a 1000).
2. **Descargas**: inicio, gestión automática, carpeta, categoría, etiquetas, renombrado,
   concurrencia, lote e intervalo. Se utiliza el primer torrent de cada revisión.
3. **Políticas**: acciones independientes sobre revisiones torrent y Calibre,
   conservándolas por defecto. Torrent se limpia después de completar la descarga;
   Calibre, después de verificar el nuevo EPUB. Los EPUB que un trabajo de Calibre
   todavía necesita no se eliminan.
4. **Programación**: resumen, biblioteca de destino cuando corresponda, confirmación
   explícita de las políticas de borrado y cron.

Los valores iniciales se copian de Ajustes, pero las opciones quedan guardadas en
la tarea. Cambiar posteriormente los valores predeterminados no cambia la tarea.
Un cambio de conexión o biblioteca exige revisar y guardar de nuevo la automatización.
Se necesitan los permisos de cada operación, y los permisos de borrado si se eligen
esas políticas. Los permisos del propietario se comprueban al ejecutar y al enviar
los trabajos a los clientes.

La columna **Ciclo** enlaza los trabajos creados y distingue **Pendiente**,
**Requiere atención** y **Terminado**. La última ejecución de la tarea corresponde a
la planificación; el ciclo no termina hasta completar los envíos y las limpiezas.
El cron y **Ejecutar ahora** no crean otro ciclo mientras quede uno pendiente.
Desactivar la tarea impide nuevas ejecuciones; los trabajos ya creados siguen su curso.

En las siguientes ejecuciones se pueden reintentar automáticamente los elementos
fallidos, con el límite configurado (uno inicialmente; de cero a tres). Los reintentos
conservan el historial y no confirman cambios de destino o de archivo automáticamente.
Los envíos inciertos, trabajos pausados y fallos que agoten ese límite requieren
revisión desde sus trabajos. Los reintentos manuales también se siguen desde el ciclo.

**Cerrar ciclo** permite volver a buscar en la siguiente ejecución, pero exige
terminar o cancelar primero los trabajos y verificar cualquier envío incierto.
No cancela ni elimina trabajos. Se conservan los últimos 20 ciclos completados,
además del ciclo pendiente.

Los ciclos y su configuración persisten en la base de datos. Un reinicio recupera
los trabajos existentes; no crea automáticamente duplicados. Las ejecuciones mediante
cron mantienen la política de logs y Eventos de las tareas programadas.

## Eliminar tareas y límites de automatización

Cualquier administrador puede gestionar las tareas de la instalación, aunque no
sea su propietario. Editarlas no cambia el propietario ni los permisos utilizados
para ejecutarlas.

**Eliminar…** requiere confirmación y solo está disponible si la tarea no está
ejecutándose ni conserva un ciclo pendiente. Si lo hay, completa o cancela sus
trabajos y cierra el ciclo antes de eliminar la tarea. Se borran su configuración
y sus registros de ciclos; los trabajos y los Eventos generados se conservan.

Solo se permite una tarea **Actualizar descargas** y una **Actualizar biblioteca
de Calibre** por instalación, incluidas las desactivadas. Puedes editar o eliminar
la existente. Las sincronizaciones, importaciones y búsquedas admiten varias tareas.

## Actividad en reposo

Las colas vacías de Torrent, Calibre, limpieza y Telegram, el programador y los
ciclos de actualización omiten consultas repetidas hasta que se confirma una
escritura. Las escrituras revertidas no despiertan las colas. Se mantiene un sondeo
de respaldo cada 30 segundos para detectar cambios externos.

Los trabajos activos conservan su frecuencia de procesamiento. Las comprobaciones
aplazadas, los reintentos y las próximas ejecuciones cron respetan su fecha prevista;
no esperan al sondeo de respaldo. Los envíos inciertos siguen verificándose antes
de repetir operaciones remotas. Las tareas de retención conservan sus intervalos.

`EPLSYNC_LOG_LEVEL=DEBUG` no activa las trazas SQL de JDBC. Para diagnosticarlas
expresamente, usa `EPLSYNC_SQL_LOG_LEVEL=DEBUG` y vuelve a `INFO` al terminar.
