# Calibre

[Guías](README.md) · [Inicio](../README.md)

La integración permite consultar bibliotecas y enviar EPUB mediante el servidor de contenido de
Calibre. Funciona con Calibre nativo en Windows/Linux y con la imagen
`lscr.io/linuxserver/calibre`. No requiere instalar `calibredb` en EPL Sync.

## Configuración

Un administrador configura la conexión en **Ajustes → Calibre**:

1. Activa el servidor de contenido en Calibre. Puedes configurar su inicio
   automático en Preferencias → Compartir por la red.
2. Introduce su URL y, si requiere autenticación, el usuario y la contraseña del
   servidor de contenido.
3. Pulsa **Comprobar conexión**. Se utilizan los datos actuales del formulario,
   sin guardarlos, y se muestran las bibliotecas accesibles.
4. Selecciona la biblioteca y pulsa **Guardar**. Se comprueba nuevamente que
   está disponible antes de guardar la configuración compartida.
5. Abre **Calibre → Biblioteca** y pulsa **Sincronizar biblioteca**.

Ejemplos de URL:

- Calibre nativo: `http://192.168.2.5:8080`.
- LinuxServer en la misma red Docker: `http://calibre:8081`.

En Docker, `localhost` identifica al contenedor de EPL Sync. Para conectar con
Calibre en el host o en otro contenedor, utiliza una dirección accesible desde
EPL Sync. En LinuxServer, 8080/8181 corresponden al escritorio remoto; 8081 es el
puerto habitual del servidor de contenido, que debe estar activo. Sus cuentas son
independientes de la contraseña del escritorio remoto.

La contraseña se cifra en la base de datos utilizando `EPLSYNC_SECRET_KEY`, igual
que las credenciales de qBittorrent. El campo queda vacío después de guardar:
vacío conserva la contraseña existente si no cambia la URL ni el usuario. Puedes
eliminarla explícitamente con su checkbox. Al cambiar de destino o usuario, las
credenciales anteriores no se reutilizan. Si falta la clave de cifrado, el
formulario ofrece el diálogo de generación antes de poder guardar una contraseña.

## Consulta

Los identificadores EPL y las revisiones se leen de los metadatos de Calibre:

```json
{"identifiers": {"epl": "80899", "revision": "1.0"}}
```

No se infieren coincidencias por título. Los libros sin identificador EPL válido
no se vinculan al catálogo.

Las consultas están paginadas (máximo 100 libros) y los metadatos se solicitan en
lotes. Se admiten hasta dos operaciones simultáneas, respuestas de hasta 2 MiB y
un plazo de 10 segundos por petición. No se siguen redirecciones con credenciales:
configura la URL final. Se admite autenticación Basic y Digest.

## Información en la ficha del libro

Pulsa **Sincronizar biblioteca** en Calibre → Biblioteca después de guardar la
conexión. EPL Sync guarda una copia local de los títulos, autores, identificadores EPL y las
revisiones, sin descargar EPUB ni modificar Calibre. Repite la sincronización
cuando cambie tu biblioteca; no se consulta Calibre al navegar por el catálogo.

La ficha del libro muestra las copias vinculadas mediante
`identifiers.epl`, con sus IDs y revisiones de Calibre. Una revisión inválida o
ausente no impide identificar el libro, pero no permite comparar versiones.
La tabla de la ficha muestra las copias con paginación, las mismas columnas que
Biblioteca y los controles **Ordenar** y **Columnas**. Aparece después del
historial de descargas.

Cada fila compara su revisión de Calibre con la del catálogo.
La biblioteca y la fecha de la última sincronización se muestran en
**Calibre → Biblioteca**.
Los datos de la biblioteca requieren el permiso `CALIBRE_READ`;
la URL y las credenciales siguen restringidas al administrador.

Las columnas y los filtros de Calibre en la tabla Catálogo quedan para una fase
posterior.

La sincronización procesa páginas de 100 libros, con un máximo de 500000 libros y
10 minutos. Solo puede ejecutarse una a la vez. Los datos se publican juntos al
completarla: un error conserva la copia anterior y elimina los datos incompletos.
Al cambiar URL, usuario o biblioteca, se descarta la copia anterior y es necesario
sincronizar otra vez. Las importaciones del catálogo no eliminan esa copia.

Descargas → Estado permite enviar los EPUB descargados a Calibre. Calibre →
Actualizaciones permite descargar revisiones nuevas, enviarlas y limpiar
las revisiones anteriores tras verificar la nueva copia.

## Biblioteca de Calibre

La sección **Calibre > Biblioteca** consulta la copia local sincronizada. Muestra
una fila por registro de Calibre, incluidos los libros con el mismo EPL ID y
revisiones distintas. Incluye EPL ID, libro con portada del catálogo, autor, revisión en Calibre,
revisión del catálogo, comparación y presencia en el catálogo. El ID de Calibre es la primera columna por defecto; no dispone de filtro propio.

Puedes buscar por EPL ID, título o autor y filtrar por comparación y presencia. Las
columnas se pueden ordenar, ocultar, reordenar y redimensionar. La consulta está
paginada, con un máximo de 1000 registros por página.

- **Consultar biblioteca de Calibre (`CALIBRE_READ`)** permite consultar esta
  vista y los datos de Calibre de las fichas de libros.
- **Sincronizar biblioteca de Calibre (`CALIBRE_SYNC`)** permite actualizar la
  copia local e implica el permiso de consulta.

La configuración de conexión continúa siendo exclusiva de administradores.
Abrir una ficha del catálogo requiere además `CATALOG_READ`. La consulta y la sincronización no modifican los libros de Calibre.

Las copias anteriores deben volver a sincronizarse para completar título y autores.

## Abrir libros en Calibre

En la ficha del catálogo, **Ver en Calibre** abre una nueva pestaña. Si hay
varias copias del mismo EPL ID, permite elegir la revisión y su Calibre ID.
El desplegable incluye paginación para consultar todas las copias. El enlace
**Calibre ID** de cada fila abre directamente esa copia, tanto en Biblioteca
como en la ficha individual. Se requiere `CALIBRE_READ`.

En **Ajustes → Calibre**, la **URL para abrir en el navegador** es opcional.
Si está vacía, se utiliza la URL del servidor de contenido. Configúrala cuando
el navegador necesite una dirección distinta de la utilizada por el backend,
por ejemplo una dirección pública en lugar del nombre de un contenedor.
Puede incluir el prefijo de un proxy, como `https://books.example/calibre`.

Esta URL no cambia la conexión del backend ni invalida la biblioteca
sincronizada. Los enlaces no incluyen las credenciales guardadas; Calibre
puede pedir iniciar sesión al abrirlos. Ambas URL admiten HTTP/HTTPS y rechazan
credenciales, parámetros y fragmentos.

## Descargar EPUB

El icono **Descargar EPUB**, con tooltip, está disponible en la columna **Acciones** de cada fila de **Calibre → Biblioteca**
y en el apartado Calibre de la ficha individual. Usa el Calibre ID y la biblioteca
configurada, por lo que permite descargar revisiones diferentes del mismo EPL ID.
No requiere acceso a las carpetas de Calibre ni instalar `calibredb`.
El icono **Ver en Calibre** abre esa copia en una pestaña nueva y solo requiere
`CALIBRE_READ`. La columna Acciones se puede ocultar y reordenar desde **Columnas**;
no se utiliza como criterio de ordenación.

Con la primera columna puedes seleccionar varios libros y pulsar **Descargar**:
un libro se entrega como EPUB y varios como ZIP. Los nombres incluyen el Calibre ID
para distinguir las copias. La selección se limita a la página visible y se limpia
al cambiar filtros, ordenación o página. Si falta el formato EPUB o falla el acceso,
se muestra el error antes de iniciar la descarga; no se entrega un ZIP parcial.

Se requiere **Descargar EPUB de Calibre** (`CALIBRE_FILES_READ`), que implica
`CALIBRE_READ` y no concede sincronización ni descargas del cliente torrent.
Los administradores disponen de este permiso automáticamente. Las credenciales
se utilizan exclusivamente en el backend y no se incluyen en los enlaces.

Los archivos se preparan temporalmente, sin cargarlos completos en memoria, y se
eliminan al terminar la entrega. Los enlaces son de un solo uso, pertenecen a la
sesión que los creó y caducan tras dos minutos; las copias abandonadas se eliminan
en la siguiente comprobación, cada 30 segundos. Un cambio en la conexión invalida
las descargas pendientes.

Límites: 1000 libros por selección, 256 MiB por EPUB y 512 MiB en total; dos
preparaciones o entregas pendientes simultáneas. La preparación tiene un plazo de
cinco minutos, con hasta 60 segundos por archivo. Durante la creación de los ZIP,
el uso temporal de disco puede alcanzar aproximadamente 2 GiB entre ambas
operaciones. Se comprueba que la respuesta sea un EPUB y no se siguen redirecciones.
La descarga utiliza el servidor de contenido; la URL opcional del navegador solo
sirve para abrir las fichas de Calibre.

## Enviar EPUB desde el cliente torrent

En **Descargas → Estado** y en el **Historial de descargas** de la ficha de un libro,
usa el icono **Enviar a Calibre** o selecciona varios registros y pulsa ese botón.
El formulario permite elegir una biblioteca accesible y los EPUB que se enviarán.
Si un torrent contiene varios EPUB, puedes desmarcar los que no quieras enviar.

El envío requiere `CALIBRE_SEND` y `DOWNLOADS_FILES_READ`. El permiso de envío
implica `CALIBRE_READ`, pero no concede acceso a los archivos del cliente torrent:
es necesario permitir también su lectura. Calibre debe tener activo su servidor
de contenido y la cuenta configurada debe poder **realizar cambios** en la biblioteca.
Las cuentas de solo lectura o con restricciones por libro pueden ser rechazadas
por Calibre. No necesitas instalar `calibredb` ni compartir las carpetas de Calibre.

Se utilizan las carpetas configuradas en **Ajustes → Torrent**, igual que para
**Descargar EPUB**. EPL Sync consulta los archivos completados del hash y lee el
EPUB desde el montaje local seguro. El original no se borra ni se modifica.

Antes de enviar, EPL Sync valida el EPUB y comprueba que los identificadores `epl` y
`revision` presentes coincidan con el registro de descarga. También permite enviarlo
si falta alguno o ambos; el detalle del resultado indica qué identificadores faltan.
Solo cuando están ambos se busca esa revisión en Calibre usando sus identificadores
reales, no el título ni una copia local antigua:

- Si ya existe el mismo EPL ID y revisión, el resultado es **Ya existe**.
- Si existe otra revisión, se añade una copia independiente y se conserva la anterior.
- Si el EPUB es inaccesible o sus identificadores no coinciden, se registra el error.

## Trabajos de Calibre

**Calibre → Trabajos de Calibre** muestra los envíos, su usuario, biblioteca,
estado y resultados. El detalle incluye un informe plegable y la tabla de archivos
con EPL ID, nombre, revisión, estado, Calibre ID y detalle. Las tablas permiten
ordenar, configurar columnas, redimensionarlas y paginar.

`CALIBRE_SEND` o `CALIBRE_JOBS_MANAGE` permiten consultar los trabajos.
`CALIBRE_JOBS_MANAGE` permite **Pausar**, **Reanudar** y **Cancelar**.
Estas acciones afectan a los archivos pendientes; un envío que ya está en curso
puede finalizar y se conservará su resultado. Cancelar no borra libros de Calibre.

Los trabajos se guardan en base de datos y se procesan por orden de creación,
con un único archivo en envío. No mantienen una transacción de base de datos
abierta durante las llamadas a qBittorrent o Calibre. Los cambios de destino o
carpetas y la pérdida de permisos del usuario detienen los siguientes envíos.
Las contraseñas no se guardan dentro de los trabajos. El inicio y el resultado
general se agrupan en una operación de Eventos por trabajo; los controles se
registran con el usuario correspondiente. Los resultados individuales se conservan
en el detalle del trabajo y en los logs DEBUG, sin crear un evento por archivo.

Si una respuesta de envío se pierde, el registro queda **Sin confirmar** y el
trabajo se pausa. **Verificar envíos** vuelve a consultar Calibre: si encuentra
la revisión, confirma el resultado; si no puede confirmarla, mantiene la pausa.
No vuelve a subir automáticamente ese archivo. Esta protección también funciona
tras reiniciar EPL Sync, y evita reenvíos mientras exista una solicitud sin confirmar.
Un añadido realizado simultáneamente desde otra aplicación sigue pudiendo coincidir
con el envío: el servidor de Calibre no ofrece una operación atómica por EPL ID y revisión.

Los libros verificados actualizan la copia local cuando el destino es la biblioteca
configurada. Si todavía no había una sincronización completa, se indica que la copia
es parcial; **Sincronizar biblioteca** obtiene el listado completo. Los envíos a
otras bibliotecas quedan reflejados en sus trabajos.

Límites: 1000 EPUB por trabajo, 256 MiB por EPUB y 10000 archivos pendientes en total.
Se usa un archivo temporal por envío, eliminado al terminar; los temporales
abandonados se revisan cada hora. Los trabajos terminados o cancelados se conservan
90 días. Los que tengan envíos sin confirmar no se eliminan por retención.

Las tablas de trabajos se incorporan mediante la migración
`V1_2_0__calibre_transfers.sql`; las migraciones publicadas no se modifican.

Los logs INFO muestran el inicio y el final del trabajo con sus resúmenes.
Los registros de cada archivo y los controles del trabajo se escriben en DEBUG.
Los EPUB sin EPL ID o revisión no permiten comprobar duplicados por esa combinación.
Si la respuesta de un envío sin identificadores se pierde y no hay Calibre ID confirmado,
el trabajo queda pendiente de verificación y no se repite automáticamente.

Si el tamaño local del EPUB difiere del registrado por qBittorrent, la preparación
muestra un aviso y permite continuar. El aviso se conserva en el detalle del trabajo,
incluso después de finalizar el envío. Las descargas individuales y múltiples también
advierten antes de iniciarse. La comprobación compara tamaños; no verifica que el
contenido del archivo sea idéntico al original.

## Actualizaciones

En **Calibre > Actualizaciones** se comparan las revisiones de la biblioteca
sincronizada con el Catálogo. Se muestra una entrada por EPL ID, usando la
revisión más alta conocida en Calibre. Si esa revisión ya es igual o superior a
la del Catálogo, el libro no aparece. Los libros sin EPL ID o sin revisión quedan
fuera de la comparación.

**Buscar actualizaciones** sincroniza la biblioteca antes de actualizar los
resultados y requiere `CALIBRE_SYNC`; consultar los resultados requiere
`CALIBRE_READ`. Se comparan los datos del Catálogo importado en EPL Sync.
Si la copia local es parcial, sincroniza la biblioteca para obtener una comparación completa.

La columna **Nueva revisión descargada** indica **Sí**, **No** o **No accesible**
y permite ordenar y filtrar. Se comprueba durante la búsqueda manual y requiere
`DOWNLOADS_FILES_READ` y `CATALOG_READ`. Una búsqueda guardada sin esa comprobación
muestra **Sin comprobar** hasta realizar otra búsqueda.

Selecciona libros y pulsa **Enviar actualizaciones**. El diálogo comprueba otra vez
los archivos y resume cuántos se enviarán a Calibre y cuántos necesitan descarga.
Los EPUB ya descargados se envían por defecto. Si hay varios archivos, elige uno por libro. Para los
EPUB no disponibles, activa **Enviar a descargar** y elige **Solo descargar** o
**Enviar a Calibre cuando estén descargados**. La primera opción crea únicamente
el trabajo de Descargas para esos libros; la segunda crea también un trabajo
persistente de Calibre que espera la descarga. Una selección mixta puede crear
ambos trabajos, enlazados desde el diálogo.

Se requieren `CALIBRE_SEND`, `DOWNLOADS_FILES_READ` y `CATALOG_READ`; para solicitar
descargas, también `TORRENT_SEND`, y para los envíos diferidos, `TORRENT_SYNC`.
Los trabajos fijan la revisión solicitada y los hashes enviados, sin sustituirlos
por otra revisión que aparezca posteriormente en el catálogo.

La acción general se puede adaptar con **Personalizar por libro**. Puedes
conservar las revisiones anteriores (opción predeterminada),
eliminar solo su EPUB o eliminar el libro completo de Calibre. La eliminación
requiere `CALIBRE_DELETE`. El borrado completo tiene una confirmación adicional:
también elimina otros formatos y datos asociados. Solo se actúa sobre los Calibre ID
seleccionados con el mismo EPL ID y una revisión inferior a la nueva. Los EPUB
originales del cliente torrent permanecen intactos.

La limpieza empieza después de verificar el EPUB y los identificadores de la nueva
copia. Un fallo deja **Limpieza pendiente** y pausa el trabajo; al reanudar se
comprueba el resultado sin volver a subir el EPUB. Si falta un identificador,
se permite el envío pero se mantiene la limpieza pendiente hasta poder verificarlo.
Los metadatos se comprueban de nuevo antes de cada borrado.

Las descargas pendientes se consultan en conjunto cada 30 segundos. Los fallos,
cancelaciones, archivos inaccesibles o esperas superiores a 30 días quedan visibles
y pausan el trabajo. Reanudar vuelve a comprobar la descarga. Cancelar un trabajo
de Calibre no cancela los torrents ya enviados ni deshace envíos o borrados completados.
Los reinicios conservan las esperas; un envío interrumpido se verifica antes de continuar.
Reintentar el mismo envío del diálogo reutiliza sus trabajos durante 90 días.

Las vistas de actualizaciones conservan el resultado y la fecha de la última
búsqueda manual por usuario en este navegador. Recargar o volver a abrir la
vista no consulta al servidor ni recalcula la comparación. La primera vez se
muestra la tabla vacía. **Buscar actualizaciones** renueva el resultado; si
falla, se conserva la búsqueda anterior. Borrar los datos del sitio elimina
estas búsquedas guardadas; no se comparten entre dispositivos.

## Limpieza manual de revisiones antiguas

Consulta [Limpiar revisiones antiguas](limpieza-revisiones.md) para preparar una limpieza con previsualización, selección y seguimiento por trabajo.
