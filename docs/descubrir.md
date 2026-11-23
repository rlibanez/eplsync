# Descubrir

[Guías](README.md) · [Inicio](../README.md)

En **Biblioteca → Descubrir** puedes buscar en el Catálogo libros de los
**autores**, **colecciones**, **géneros**, **idiomas** o **años de publicación**
que ya están presentes en tus Descargas, en Calibre o en ambos. Los resultados son coincidencias del Catálogo; no necesariamente
publicaciones recientes.

## Preparar la búsqueda

1. Elige **Basarse en**: Descargas, Calibre o Ambos, según tus permisos.
2. En **Buscar coincidencias por**, selecciona uno o varios criterios: Autores,
   Colecciones, Géneros, Idiomas y Año de publicación.
3. Elige **Combinar criterios → Todos** para exigir una coincidencia en cada
   criterio, o **Cualquiera** para aceptar al menos uno. Dentro de cada criterio,
   basta con coincidir en uno de sus valores.
4. Todo está seleccionado por defecto. Pulsa el contador de **Disponibles** de
   cada criterio para marcar o desmarcar valores y confirma con **Aplicar selección**.
5. En **Excluir presentes en**, marca Descargas, Calibre o Trabajos pendientes.
   El desplegable tiene casillas, resumen de altura fija, tooltip con la selección
   completa y una X para desmarcar las exclusiones disponibles. Sus opciones
   respetan tus permisos.
6. Pulsa **Buscar**. Puedes combinarlo con los filtros habituales del Catálogo.

Los cuatro campos superiores usan la misma cuadrícula y los mismos anchos que
la búsqueda avanzada. Se distribuyen en varias columnas según el espacio disponible
y en una columna en móvil.

Las exclusiones de Descargas y Calibre comparten los filtros de la búsqueda
avanzada: marcar una equivale a **Presencia = Ausentes**. Puedes modificarlas
desde cualquiera de los dos apartados. Elegir **Presentes** o borrar la presencia
desmarca la exclusión. Los filtros de revisión y estado incompatibles con
«Ausentes» se limpian al marcarla.

La búsqueda utiliza los datos locales de la última sincronización. No consulta
qBittorrent ni Calibre y no inicia sincronizaciones ni envíos automáticamente.
El encabezado muestra las últimas sincronizaciones de Descargas y Calibre,
según tus permisos. **Sincronizar todo** actualiza ambas fuentes; si solo puedes
sincronizar una, el botón identifica esa fuente. Cada sincronización muestra su
estado y sus errores por separado, con opción de reintentar. Al terminar se
actualizan los valores disponibles; pulsa **Buscar** para actualizar los resultados.

Para detectar los valores se toman las descargas completadas del cliente
actual y los libros activos de Calibre identificados con un EPL ID. Sus metadatos
se obtienen del Catálogo. Los libros sin correspondencia por EPL ID no se utilizan
como punto de partida.

Las exclusiones se aplican por EPL ID: una revisión anterior también cuenta como
libro presente. En Descargas no cuentan como presentes los registros «No
encontrado»; en Calibre no cuentan los registros inactivos. Los envíos pendientes
se limitan a los trabajos accesibles y al destino actual.

Por ejemplo, con **Autores + Géneros → Todos**, cada resultado debe tener algún
autor y algún género de los detectados o seleccionados. **Cualquiera** permite
que coincida solo el autor o solo el género. No es necesario que el autor y el
género procedan del mismo libro del origen. Los valores ausentes no cuentan como
coincidencia; los años desconocidos o iguales a cero se ignoran. Los géneros se
separan por comas, igual que en el Directorio.

## Trabajar con los resultados

La tabla reutiliza las acciones del Catálogo: seleccionar libros, seleccionar
todos los resultados, exportar y enviar a descargar o a Calibre, según los
permisos. Cada libro aparece una sola vez. La columna **Coincidencia** muestra
el criterio, el valor coincidente y el origen donde se ha detectado.

**Limpiar**, junto a **Buscar**, restablece las opciones predeterminadas: Descargas
(o Calibre si solo tienes acceso a ese origen), Autores, Todos y todos los valores
detectados. Reactiva las exclusiones disponibles y los trabajos pendientes, vacía
los filtros avanzados y deselecciona la búsqueda guardada. No elimina ninguna
búsqueda guardada. También restablece los cambios que aún no hayas aplicado.

**Guardar búsqueda**, junto a **Buscar**, permite guardar la configuración sin
aplicarla primero. Se conservan el origen, los criterios, Todos/Cualquiera, los
valores seleccionados, las exclusiones y los filtros avanzados. Las búsquedas
son personales y específicas de Descubrir: no aparecen en Catálogo ni comparten
la gestión con sus búsquedas. Puedes usar el mismo nombre en ambos buscadores.

**Buscar coincidencias por** mantiene una altura fija: al abrirlo se marcan los
criterios mediante casillas. Cerrado muestra todos los nombres que caben en el espacio disponible y un
contador de los restantes; el resumen se reajusta al cambiar el ancho. El tooltip
muestra la selección completa. La selección también
se puede manejar con el teclado.

La tabla se alinea con las dos primeras columnas del formulario, incluido el
espacio entre ellas; en móvil ocupa el ancho disponible.

La tabla de criterios tiene las columnas **Criterio**, **Disponibles** y
**Seleccionados**. Se muestra siempre el número de valores seleccionados que
siguen disponibles, independientemente de si la selección se guardó como una
lista concreta o como «todos salvo estos». Al abrir el contador de disponibles,
una tabla muestra casillas y el número de libros que aportan cada valor desde
Descargas y Calibre. **Quitar selección (n)** indica cuántos valores seleccionados
se desmarcarán en todo el criterio.

La casilla de la cabecera selecciona o deselecciona la página visible. Los botones
**Seleccionar todos** y **Quitar selección** actúan sobre todo el criterio,
aunque haya una búsqueda de texto activa. La selección se conserva al buscar o
cambiar de página; **Cancelar** o cerrar la ventana descarta los cambios locales.
Una selección vacía no produce coincidencias para ese criterio. Se admiten hasta
10.000 valores o exclusiones por criterio, y «todos salvo estos» no enumera los
miles de valores que siguen seleccionados.

La combinación Todos/Cualquiera muestra su explicación al pasar el ratón o enfocar
el título. La X de **Buscar coincidencias por** quita todos los criterios; debes
seleccionar al menos uno para buscar.

La columna **Coincidencia** agrupa los valores por criterio y reúne sus orígenes
en etiquetas de Descargas y Calibre, sin repetir el valor compartido.

Los valores de descubrimiento no se copian a los campos Autor, Idioma, etc. de la
búsqueda avanzada: estos campos se combinan con «y» y sus reglas de texto pueden
ser distintas. Copiarlos cambiaría los resultados, especialmente con Cualquiera.
Los filtros de presencia sí se comparten en ambos sentidos.

Las columnas y la vista se recuerdan independientemente del Catálogo habitual.
También se recuerda en este navegador la última configuración de descubrimiento;
para ejecutarla de nuevo, pulsa **Buscar**. Al abrir una ficha y volver se conserva
la búsqueda.

La separación de búsquedas guardadas añade una migración automática que conserva
los identificadores y las configuraciones existentes. Las que contienen un origen
de descubrimiento se asignan a Descubrir y el resto a Catálogo.

No se necesitan nuevos permisos: se requiere
lectura del Catálogo y acceso al origen utilizado. Los permisos existentes siguen
controlando las acciones sobre los resultados.

En **Autores** puedes limitar los libros utilizados para detectar autores:

- **Ignorar libros de «AA. VV.» al detectar autores** descarta de la detección
  todos los autores de esos libros.
- **Ignorar libros con más de X autores** permite establecer un máximo entero
  mayor que cero; los libros con exactamente X autores sí se tienen en cuenta.

Ambas opciones están desactivadas por defecto, pueden combinarse y se guardan
con las búsquedas de Descubrir. Solo afectan al origen de los autores: no impiden
que un libro con varios autores aparezca entre los resultados. Un autor sigue
estando disponible si aparece en otro libro que cumpla estas condiciones.

La **Búsqueda avanzada** también ofrece un recuadro **Autores**, tanto en Catálogo
como en Descubrir: **Excluir libros de «AA. VV.»** y **Excluir libros con más de
X autores**. Estas opciones sí excluyen libros de los resultados, se combinan con
el resto de filtros y se conservan en las búsquedas guardadas de cada buscador.
Las exportaciones y la selección de todos los resultados respetan las exclusiones.

El contador de **Disponibles** de cada criterio permite abrir la lista de autores,
colecciones, géneros, idiomas o años detectados, buscar y recorrer las páginas.
**Aplicar selección** modifica la configuración; pulsa **Buscar** para actualizar
los resultados de libros. Para cada
valor se indica cuántos libros lo aportan desde Descargas y Calibre. Los idiomas
se muestran traducidos y se pueden buscar por su nombre o código.

Las opciones de ignorar libros y su resumen se agrupan en un bloque independiente
**Detección de autores**, debajo de la tabla de criterios. Tiene el mismo ancho que la tabla de criterios. El resumen se muestra debajo
de las opciones en una línea compacta, adaptable a varias líneas en móvil. Al activar exclusiones de detección una línea de resumen muestra cuántos libros se ignoran por
cada motivo y el total. En **Ambos**, un libro presente en las dos fuentes se
cuenta una sola vez. Los libros afectados por ambas exclusiones también se
cuentan una sola vez en el total, con el solapamiento indicado por separado.

Después de buscar, **Hay cambios sin aplicar** avisa si se modifica la
configuración de Descubrir o sus filtros avanzados. El aviso desaparece al
restaurar las opciones anteriores, ejecutar la búsqueda o cargar una guardada.

En las ventanas de selección, el contador y **Quitar selección (n)** se actualizan
al instante al marcar o desmarcar casillas. Se parte del recuento validado al abrir
la ventana y se contabilizan los cambios visibles sin consultar al servidor en
cada clic. La selección aplicada se vuelve a validar con los valores actuales.

El resumen de libros ignorados permanece visible aunque ambas opciones estén
desactivadas: muestra cero ignorados, el total de libros analizados y cero por
cada motivo no activado.

Las ventanas de valores detectados utilizan la cabecera y el pie comunes de la
aplicación. La paginación sigue el formato **Anterior · página / total · Siguiente**,
alineado a la derecha, y el recuento seleccionado aparece junto a las acciones.
