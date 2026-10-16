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
asociado. Selecciona libros para enviarlos al cliente o exportar sus enlaces magnet.

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

El filtro **Estado** de Torrent permite seleccionar varios estados (cualquiera de ellos), vacío por defecto. Seleccionarlo implica presencia; elegir «Ausentes» lo limpia y desactiva. Se consulta el estado registrado de las revisiones del cliente actual: basta con que alguna tenga un estado seleccionado. El criterio de revisión sigue comparando la mayor revisión presente con el catálogo.
