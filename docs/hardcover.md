# Identificadores de Hardcover en Calibre

[Guías](README.md) · [Calibre](calibre.md)

«Completar Hardcover» busca por el título y los autores actuales de la entrada de
Calibre, utilizando la API GraphQL de Hardcover. Aparece tanto en las acciones de
selección de Biblioteca de Calibre como en las acciones de cada fila. Requiere
`CALIBRE_READ` y `CALIBRE_SEND`. No necesita leer las descargas ni los EPUB.

## Configuración

Un administrador puede introducir la API key en **Ajustes > Calibre > Hardcover** y
comprobar la conexión antes de guardarla. La comprobación usa el campo actual,
pero no guarda ni modifica la configuración. Los cambios guardados se aplican
sin reiniciar. El token se obtiene en <https://hardcover.app/account/api>.

La clave se comparte en esta instalación. Se cifra en la tabla existente
`app_settings` mediante `EPLSYNC_SECRET_KEY` y nunca se devuelve al navegador.
El campo vacío conserva la clave actual. «Borrar la API key y desactivar la
integración» impide también usar la clave de entorno.

También se admite `EPLSYNC_HARDCOVER_API_KEY` en `.env` o al ejecutar el JAR.
Cambiar esa variable requiere recrear el contenedor o reiniciar el JAR. La clave
guardada en Ajustes tiene prioridad y no se copia a `.env`; la clave de entorno
no se copia a SQLite. Conserva `EPLSYNC_SECRET_KEY` para recuperar las claves
cifradas tras reiniciar o restaurar una copia de seguridad.

Sin clave se puede utilizar EPL Sync, pero no consultar Hardcover. Las consultas
son de lectura: no modifican tu cuenta de Hardcover. No se añaden tablas ni
migraciones.

La configuración incluye un enlace para obtener o renovar la API key. Si el
token contiene la fecha `exp`, se muestra su caducidad y se avisa cuando ha
caducado o le quedan siete días o menos. Solo se devuelve esa fecha y sus
indicadores, nunca el token ni sus otras propiedades. Esta lectura local no
valida la firma ni garantiza que Hardcover acepte la clave: la comprobación de
conexión sigue siendo la referencia. Los tokens sin fecha reconocible no
reciben una caducidad estimada.

### Uso y límite

El apartado muestra la cuota diaria y las consultas disponibles comunicadas en
las cabeceras `RateLimit` y `RateLimit-Policy` de las respuestas normales de
Hardcover. No necesita una consulta adicional ni realiza sondeo en segundo
plano. La fecha de la última respuesta y el restablecimiento acompañan al dato;
no se estima ni se presenta como un contador en tiempo real. Puede incluir uso
realizado desde otros clientes que compartan la misma cuota.

Los datos se conservan temporalmente en memoria, separados por credencial.
Tras reiniciar, cambiar a una clave aún no consultada o vencer el plazo, se
mostrará su disponibilidad tras la próxima consulta. Si las cabeceras no están
disponibles, no se inventa un límite ni un consumo.

## Datos y nombres de identificadores

El asistente ofrece un listado fijo de datos. Puedes activar cada dato y elegir
el nombre con el que se guardará en Calibre. Los nombres elegidos se conservan
solo para tu usuario, en los ajustes existentes; no se añade una tabla nueva.

| Dato | Nivel | Nombre propuesto |
| --- | --- | --- |
| Hardcover (slug) | Libro | `hardcover` |
| Hardcover ID | Libro | `hardcover_book` |
| Hardcover edition | Edición | `hardcover_edition` |
| ISBN-10 | Edición | `isbn10` |
| ISBN-13 | Edición | `isbn` |
| ASIN | Edición | `asin` |

Inicialmente se seleccionan los tres identificadores de Hardcover. Los nombres
son editables; cada dato debe usar un nombre distinto, en minúsculas y sin
espacios. `epl`, `revision`, `uuid` y `calibre` están protegidos. Para un ISBN que
otras aplicaciones deban reconocer, utiliza el identificador estándar `isbn`:
Calibre solo conserva un valor para cada nombre, no dos ISBN en ese mismo campo.

Los nombres iniciales reflejan los lectores publicados de
[Grimmory](https://github.com/grimmory-tools/grimmory/blob/develop/backend/src/main/java/org/booklore/service/metadata/extractor/EpubMetadataExtractor.java)
y [BookOrbit](https://github.com/bookorbit/bookorbit/blob/main/server/src/modules/metadata/lib/opf-parser.ts).
Grimmory distingue el slug `hardcover` y el ID `hardcover_book`. BookOrbit acepta
el slug en `hardcover` y resuelve la obra mediante su
[búsqueda por identificador o slug](https://github.com/bookorbit/bookorbit/blob/main/server/src/modules/hardcover/hardcover-book-match.service.ts);
también reconoce `hardcover_edition`. No es necesario un perfil por aplicación.
Cambiar los nombres puede impedir que una aplicación externa los reconozca.

## Buscar, previsualizar y guardar

1. Selecciona los datos que guardarás y revisa los nombres de identificador.
   La consulta inicial usa automáticamente los identificadores válidos disponibles,
   y en su defecto el título y el autor.
2. Pulsa «Buscar en Hardcover». Se usan los datos disponibles, sin una consulta GraphQL
   editable. La consulta busca en `title`, `author_names` o ambos; sus resultados se cargan en grupos de 20 al desplazarse por el desplegable,
   sin botones de paginación.
   Los ISBN y ASIN se leen de los identificadores de Calibre. Se normalizan y
   validan; los ausentes o inválidos se omiten y sus campos permanecen vacíos. Si queda
   alguno válido, se hace una búsqueda exacta de la obra por sus ediciones. ISBN
   y ASIN deben coincidir en la misma edición cuando ambos estén disponibles.
   Un resultado vacío no se amplía automáticamente por título o autor. Si no
   queda ningún identificador válido, se usan título y autor solo si están
   marcados y disponibles. Sin criterios utilizables no se consulta la API.
3. La tabla muestra desde el principio los identificadores actuales de Calibre,
   aunque aún no se haya seleccionado una obra de Hardcover. Los valores
   propuestos permanecen vacíos y no se puede guardar hasta comprobar una
   selección. No se lee el EPUB para obtener estos datos.
   Puedes ajustar «Título» y «Autor» en cada libro.
   También puedes introducir ISBN y ASIN manualmente aunque falten en Calibre.
   «Buscar en Hardcover» utiliza los campos rellenos; los identificadores válidos
   tienen prioridad para la consulta exacta. Un ISBN o ASIN manual inválido muestra
   un error sin consultar la API y permite corregir el campo. Puedes dejar los
   demás campos vacíos para usar solo un criterio. Estos cambios solo afectan a la consulta,
   no a los metadatos de Calibre. La paginación conserva la consulta utilizada.
   La búsqueda por título incluye el título principal y los títulos alternativos de la obra,
   también cuando se combina con el autor, dentro de la misma petición.
   Selecciona la obra correcta. Las búsquedas de texto priorizan el título exacto, después la relevancia y
   desempatan por número de valoraciones. Las búsquedas exactas de identificadores
   ordenan las obras por valoraciones y reseñas. Las ediciones se ordenan por
   número de usuarios. Solo se selecciona automáticamente una obra o edición
   cuando es la única de todo el conjunto; no basta con que sea la única de la
   página visible. La selección automática prepara la tabla, pero no guarda datos. En la fila `hardcover` de la previsualización
   puedes activar el lápiz, introducir un slug y pulsar el check verde
   (o Enter) para comprobarlo. Escape descarta la edición del texto. EPL Sync consulta la obra
   real y actualiza el ID propuesto y las opciones de edición; la edición anterior
   se descarta. También puedes editar `hardcover_edition`: se comprueba la
   edición y su relación con la obra en Hardcover para actualizar los IDs y el
   slug propuestos. La edición manual se conserva aunque inicialmente no esté
   entre las 20 opciones más populares. La selección manual queda indicada. Guardar comprueba los cambios manuales de slug, ID de obra o edición
   antes de escribir; el check es opcional para actualizar la selección antes. Al comprobar una edición manual o un slug
   manual, los cambios de los identificadores Hardcover seleccionados se marcan
   como «Se sustituirá» y pueden guardarse con «Guardar».
4. El selector de idioma muestra todos los idiomas de las ediciones de la obra,
   no solo los de la página visible. Preselecciona el idioma del navegador si
   está disponible: por ejemplo CA para `ca-ES`, FR para `fr-FR` y PT para
   `pt-BR`. Las variantes regionales usan su idioma base. Si la obra no tiene
   ediciones en ese idioma se usa «Todos». Puedes cambiarlo; el filtro se aplica en Hardcover
   antes de paginar, conserva el orden por popularidad y descarta la selección
   de edición anterior. Una única edición filtrada se selecciona automáticamente.
   Si corresponde, selecciona la edición concreta. Se muestran título, idioma,
   editorial e ISBN; también se pagina su consulta. La edición de Hardcover no
   equivale a la revisión EPL. Si no puedes identificarla, déjala vacía: se
   omitirán los datos de edición. «Abrir en Hardcover», bajo cada selector, abre
   la obra o la edición elegida en otra pestaña.
5. La tabla de valores actuales y propuestos se actualiza automáticamente al
   elegir la obra o edición. Confirma «Guardar en
   Calibre». Si seleccionaste varios libros, puedes guardar u omitir cada uno y
   continuar con el siguiente o volver al anterior. Volver a un libro consulta
   de nuevo sus metadatos actuales; no deshace los identificadores ya guardados.

La vista agrupa la información en «Libro en Calibre» y «Libro en Hardcover»,
separando la obra de su edición y mostrando sus portadas ampliadas. La posición dentro de la
selección y el total de libros guardados aparecen junto a los botones inferiores.
Las URL de Hardcover se incluyen en las consultas existentes y se conservan en
la misma caché; mostrar las portadas no añade consultas GraphQL. Las imágenes
se descargan aparte. La portada de Calibre requiere `CALIBRE_FILES_READ`, se
sirve mediante EPL Sync sin exponer la URL ni las credenciales del servidor y
se reutiliza durante la búsqueda. Se solicita la portada completa, con un límite
de 4 MiB. Una portada ausente o fallida muestra un
marcador discreto y no bloquea la búsqueda ni el guardado. Al pulsar una portada
se abre una vista ampliada; se cierra con clic en la imagen o el fondo, Escape
o el botón de cierre, conservando el estado del asistente.

Los datos consultados se conservan temporalmente durante cada búsqueda: obras,
idiomas y páginas de ediciones se reutilizan al cambiar de selección. Las
previsualizaciones usan esas respuestas comprobadas por el servidor, no valores
propuestos por el navegador. La memoria está limitada y se libera al descartar
la búsqueda o al caducar; no se guarda en SQLite. Las consultas fallidas no se
conservan. Se sigue comprobando Calibre al previsualizar y antes de escribir.

Los valores propuestos vacíos eliminan el identificador existente al guardar.
Si ambos valores están vacíos no hay cambios. Los ISBN con formato
o dígito de control inválido y los ASIN inválidos no se guardan. Los valores que
ya coinciden no se vuelven a escribir. Los valores distintos se muestran como
«Se sustituirá» y se aplican al pulsar Guardar, incluidos los ISBN y ASIN
seleccionados. EPL, revisión y los identificadores ajenos al plan se conservan.
Los títulos, autores, portadas y archivos no se modifican.

La confirmación está ligada al usuario, biblioteca, UUID y metadatos observados.
Si cambian, hay que volver a buscar o previsualizar. Las búsquedas caducan a los
15 minutos y sus confirmaciones a los 10 minutos. Cerrar el asistente aborta las
consultas del navegador y descarta sus previsualizaciones conocidas. Una escritura
ya confirmada puede finalizar y se conserva al cerrar.

Tras guardar, EPL Sync vuelve a leer Calibre para comprobar los identificadores.
Si se pierde una respuesta, un reintento comprueba primero los valores actuales:
si ya se guardaron, no repite la escritura. Las operaciones se coordinan con los
envíos y sincronizaciones de Calibre. Las peticiones a Hardcover tienen plazo y
tamaño máximos, no siguen redirecciones ni se reintentan automáticamente, y se
limitan a una simultánea con una separación mínima de un segundo.

## Qué leerán Grimmory y BookOrbit

Esta función cambia los metadatos de la biblioteca de Calibre, **no el EPUB
almacenado**. Las aplicaciones que importan el archivo necesitan que dichos
identificadores estén incluidos en la copia que reciben. Exporta desde Calibre
con los metadatos actualizados e importa o actualiza esa copia en la aplicación
de destino. Asignarlos en Calibre no actualiza los libros que ya se importaron
en Grimmory o BookOrbit, ni incrusta identificadores en los EPUB originales.

Las opciones de obra muestran el número de valoraciones y las de edición el
número de usuarios, cuando Hardcover proporciona esos datos. Los tooltips
explican la ordenación: título exacto, relevancia y valoraciones en búsquedas
por texto; valoraciones y reseñas en búsquedas por ISBN/ASIN; usuarios en las
ediciones. Los contadores se obtienen en las consultas existentes.

Los desplegables de obra y edición muestran el texto completo de cada opción
en su tooltip. El valor seleccionado incluye también el criterio de ordenación.

## Identificadores en la Biblioteca de Calibre

La columna **Hardcover**, situada antes de **Acciones**, muestra los identificadores
Hardcover presentes en cada libro, con sus nombres y valores. El filtro rápido
permite seleccionar Hardcover, Hardcover ID y Hardcover edition: cada libro debe
contener todos los identificadores seleccionados, aunque también contenga otros.
Se respetan los nombres personalizados del usuario y el permiso de lectura de Calibre.

La información procede de la copia local de la biblioteca: se actualiza al
sincronizar Calibre y tras guardar identificadores con «Completar Hardcover».
Tras actualizar EPL Sync, sincroniza Calibre para completar estos datos en los
registros anteriores. Mostrar la columna o filtrar no consulta la API de Hardcover
ni realiza consultas individuales a Calibre.

El botón de negación del filtro Hardcover excluye los libros que contienen todos
los identificadores seleccionados; incluye los que carecen de alguno o de todos.
La cabecera Hardcover permite ordenar ascendente o descendente por los valores
del identificador de la obra, su ID y la edición, en ese orden.

Los cambios de obra y edición, también los propuestos automáticamente, se
previsualizan antes de guardar. Ninguna selección escribe en Calibre por sí sola:
Guardar aplica las altas, sustituciones y eliminaciones indicadas en la tabla.

## Libros sin correspondencia

En «Completar Hardcover», **Sin correspondencia** marca el libro como revisado
sin escribir identificadores en Calibre ni modificar sus archivos, y avanza al
siguiente libro. **Siguiente** avanza sin marcarlo. Al volver a un libro
marcado, **Quitar marca** permite dejarlo pendiente de revisión otra vez.
Guardar una correspondencia válida elimina la marca automáticamente.

Los filtros **Hardcover** (identificadores) y **Revisión Hardcover** son independientes.
Para buscar libros sin identificador pero ya revisados, selecciona **Hardcover ≠ Hardcover**
y **Revisión Hardcover = Sin correspondencia**. Para trabajar solo con libros pendientes,
usa **Hardcover ≠ Hardcover** y **Revisión Hardcover = Sin revisar**.
Cada filtro conserva su propio botón de negación. Los enlaces antiguos que incluían
la revisión dentro del filtro Hardcover siguen siendo compatibles.

Las marcas son compartidas por los lectores de esa biblioteca de la instalación.
Se guardan en EPL Sync con fecha y usuario, asociadas al servidor, biblioteca,
Calibre ID y UUID del libro. Sobreviven a sincronizaciones y reinicios; un cambio
de título, autores o identidad deja de considerarse revisado. Marcar o desmarcar
requiere los mismos permisos de lectura y envío que el wizard, y una búsqueda
vigente del mismo usuario y biblioteca. El filtro solo requiere lectura de Calibre.

**Sin revisar** muestra exactamente las filas cuya columna Hardcover es «—»:
no contienen ninguno de los tres identificadores configurados y no tienen la marca
«Sin correspondencia». Su negación incluye tanto libros con identificadores como
libros marcados sin correspondencia.

## Guardar cualquier cambio explícito de identificadores

La previsualización muestra «Se añadirá», «Se sustituirá» o «Se eliminará».
Guardar aplica esas operaciones también al cambiar la obra del desplegable.
Al cambiar a otra obra sin edición seleccionada, se elimina la edición anterior
para no vincularla a una obra distinta. Los valores vacíos mostrados en la propuesta también se guardan como
eliminaciones si había un valor previo. Todos los valores de la tabla son editables directamente. Vaciar un campo
prepara su eliminación, sin botones de papelera ni pasos adicionales. Guardar
se habilita siempre que difieran el valor actual y el propuesto. Al abrir un
libro, los valores propuestos parten de los existentes; seleccionar una obra o
edición reemplaza la propuesta con los datos consultados.

No se escribe hasta pulsar Guardar. La escritura mantiene UUID, título, autores
y los identificadores ajenos al plan, verifica el resultado y rechaza cambios
concurrentes. Los EPUB permanecen sin modificar.

## Errores de consulta y reintento

Los avisos de Hardcover incluyen un diagnóstico breve: código HTTP del servidor,
error de red (DNS, TLS, conexión o espera agotada), errores GraphQL o el campo de
respuesta que falta. Una búsqueda puede devolver HTTP 200 y aun así contener una
respuesta inválida; en ese caso el aviso identifica el problema de estructura.
No se muestran la clave, las cabeceras de autenticación ni el cuerpo completo.

**Reintentar**, a la derecha del aviso, repite la operación fallida sin cambiar de
libro. Las consultas fallidas no se guardan en la caché. Navegar, cerrar o iniciar
otra operación descarta el reintento anterior; no hay reintentos automáticos.
Las escrituras mantienen sus comprobaciones de identidad, concurrencia y resultado.

Si falla la búsqueda en Hardcover, el asistente conserva los metadatos que ha
leído de Calibre: título, autores e identificadores actuales. Los campos de
búsqueda siguen disponibles para corregir los criterios, y Reintentar repite la
consulta fallida. Un fallo no se presenta como una búsqueda sin coincidencias
ni se guarda como resultado válido en la caché.

Los fallos de conexión, HTTP, GraphQL y estructura de respuesta se registran en
`eplsync.log` con nivel WARN y diagnóstico breve, sin credenciales ni respuestas
completas. La información de Calibre y la causa de la consulta fallida se
transmiten por separado en la respuesta del asistente.
