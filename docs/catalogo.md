# Catálogo y Directorio

[Guías](README.md) · [Inicio](../README.md)

## Importar el catálogo

En **Ajustes → Catálogo → Importar catálogo** puedes utilizar una URL, un ZIP local
o el archivo guardado de una importación anterior. La URL predeterminada se puede
cambiar en **Configuración de importación**.

Previsualiza el contenido antes de importar. La actualización añade libros nuevos,
actualiza los existentes y conserva el historial de descargas. Los libros ausentes
del archivo no se eliminan automáticamente.

Un archivo vacío o inválido se rechaza. Los errores de formato o límites se
muestran en el resultado de la importación; la operación no debe dejar cambios
parciales en el catálogo.

La importación no descarga los libros: incorpora sus datos al catálogo.

## Buscar y navegar

En **Biblioteca → Catálogo**, utiliza la búsqueda avanzada para combinar filtros
de título, autor, idioma, género y otros campos. Puedes ordenar por las cabeceras
o mediante **Ordenar**; **Columnas** permite mostrar, ocultar y reordenar campos.
Los anchos de las columnas se ajustan arrastrando sus separadores.

Las vistas de tabla, cuadrícula y mosaico presentan los mismos resultados.
Abre un libro para consultar su ficha, enlaces y, si tienes permiso, el historial
asociado. Selecciona libros para enviarlos al cliente torrent, enviarlos a Calibre
o exportar sus enlaces magnet.

La columna **#** muestra la posición de cada fila, con numeración continua entre
páginas. Se recalcula al ordenar o filtrar; no es un identificador del libro.
En las tablas con selector **Columnas** puede ocultarse y reordenarse.

### Incluir y excluir valores

El botón **= / ≠** dentro de Estado, Estado de publicación e Idioma incluye o
excluye los valores seleccionados. En Título y Autor significa **Contiene** o
**No contiene**: con varios textos, la negación excluye los libros que contengan
cualquiera de ellos. Los campos vacíos no restringen resultados.

Los distintos campos se combinan con **Y**. Los operadores se conservan en la URL,
la paginación, la selección de todos los resultados y sus exclusiones. **Limpiar**
restablece la inclusión. Pulsar un valor en la tabla aplica inclusión; si ese campo
estaba en modo negativo, sustituye su selección anterior por el valor pulsado.

**Biblioteca → Directorio** agrupa autores, colecciones, idiomas, géneros y años.
Utiliza su búsqueda e iniciales para encontrar una entrada y acceder al catálogo
filtrado por ella.

### Presencia y revisión en Torrent y Calibre

La búsqueda avanzada incluye dos bloques independientes, disponibles según tus
permisos de lectura. Sus campos empiezan vacíos: no filtran hasta elegir un valor.

- **Presencia:** Presentes o Ausentes. Torrent consulta los registros del cliente
  actual y excluye los marcados como «No encontrado». Calibre consulta las entradas
  activas de la biblioteca sincronizada.
- **Revisión:** Actualizada si la revisión más alta conocida es igual o superior
  a la del Catálogo; Anterior si es inferior. Una revisión desconocida en Calibre
  cuenta como presencia, pero no coincide con ninguno de los filtros de revisión.

Elegir una revisión selecciona Presentes. Elegir Ausentes limpia y desactiva
Revisión. Los selectores permiten quitar la selección; limpiar Presencia también
limpia Revisión. Ambos bloques se combinan con los demás criterios y se conservan
en la URL, la paginación, la selección de todos los resultados y la exportación.
Se utilizan datos locales: la búsqueda no sincroniza ni consulta servicios remotos.

El filtro **Estado** de Torrent permite seleccionar varios estados, vacío por
defecto. Con **=** basta con que alguna revisión tenga un estado seleccionado;
con **≠** se excluye el libro si alguna revisión tiene uno de esos estados.
Seleccionarlo implica presencia; elegir **Ausentes** lo limpia y desactiva.
Se consulta el estado registrado de las revisiones del cliente actual. El criterio
de revisión sigue comparando la mayor revisión presente con el Catálogo.

### Enviar la selección a Calibre

La barra de selección permite **Enviar a Calibre** mediante el mismo asistente de
Estado de las descargas. Requiere permisos de lectura del catálogo, lectura de
archivos y envío a Calibre. Se puede enviar todo el Catálogo en un solo trabajo;
al seleccionar todos conserva los filtros aplicados y los libros excluidos, sin limitarse a la página visible.

Se utiliza el EPUB de la revisión del catálogo, registrado como descargado en el
cliente torrent actual. El asistente permite elegir archivos y biblioteca. Para
los EPUB no disponibles se puede activar expresamente su descarga y envío posterior
(requiere también permisos de envío y sincronización de Torrent). Se reutilizan
los trabajos de descarga pendientes de esa misma revisión cuando existen. Los
trabajos de Calibre conservan las comprobaciones de identidad y de EPUB ya existente;
esta acción no borra revisiones antiguas. Si cambia el catálogo antes de confirmar,
es necesario volver a preparar la selección.

Si un EPUB pendiente termina de descargarse mientras el asistente sigue abierto,
al confirmar se utiliza el archivo disponible sin repetir la descarga. Los archivos
que se hayan desmarcado permanecen excluidos.

## Portadas

En **Ajustes → Catálogo** están los apartados de comprobación y configuración de
portadas. La comprobación revisa la disponibilidad de las URL y muestra un informe.
No descarga imágenes para guardarlas en EPL Sync.

La ficha de un libro ofrece **Reparar portada** cuando procede. Si ya utiliza
la alternativa, el botón permanece deshabilitado y su tooltip explica el motivo.

## Limpieza del catálogo

**Ajustes → Catálogo → Limpieza del catálogo** permite revisar los libros ausentes
de un archivo e indicar cuáles eliminar. Revisa la previsualización y confirma
la selección antes de borrar.

Para el reinicio completo, utiliza el apartado correspondiente de
**Ajustes → Base de datos**. La confirmación permite elegir si también se eliminan
usuarios y configuraciones; esa opción vuelve a abrir el asistente de primer
administrador. Es una operación distinta de actualizar el catálogo.

En selecciones de más de 1000 libros ya descargados, la comprobación de sus EPUB
se realiza por lotes durante el trabajo. Si un torrent contiene varios EPUB,
el elemento requiere revisión para elegir el archivo. El asistente no consulta
todos los archivos remotos antes de crear la cola.
