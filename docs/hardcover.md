# Identificadores de Hardcover en Calibre

[Guías](README.md) · [Calibre](calibre.md)

«Completar Hardcover» busca por el título y los autores actuales de la entrada de
Calibre, utilizando la API GraphQL de Hardcover. Aparece tanto en las acciones de
selección de Biblioteca de Calibre como en las acciones de cada fila. Requiere
`CALIBRE_READ` y `CALIBRE_SEND`. No necesita leer las descargas ni los EPUB.

## Configuración del servidor

Configura `EPLSYNC_HARDCOVER_API_KEY` en `.env` y recrea el contenedor. La variable
se transmite mediante Docker Compose y también sirve al ejecutar el JAR. El token
se obtiene en <https://hardcover.app/account/api>.

La clave no se configura en Ajustes, no se copia a SQLite ni se devuelve al
navegador. Vacía permite arrancar EPL Sync normalmente, pero impide consultar
Hardcover. Las consultas son de lectura: no modifican tu cuenta de Hardcover.

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
   entre las 20 opciones más populares. La selección manual queda indicada. Mientras el slug editado
   no se compruebe no se puede guardar. Los identificadores existentes distintos
   siguen protegidos.
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

Los datos ausentes en Hardcover se omiten. También se omiten los ISBN con
formato o dígito de control inválido y los ASIN con formato inválido. Los que ya coinciden no se vuelven a
escribir. Los identificadores con un valor distinto se conservan y se señalan en
la previsualización. No se sobrescriben ISBN, EPL, revisión ni otros valores
existentes. Los títulos, autores, portadas y archivos no se modifican.

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
