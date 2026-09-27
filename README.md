# EPL Sync

## Importación del catálogo

Los tres modos descargan el ZIP oficial de ePubLibre. Admiten el parámetro
opcional `url` para indicar otro ZIP mediante HTTP o HTTPS.

### Reemplazar el catálogo

```sh
curl -i -X POST http://localhost:8080/api/catalog/import/reset
```

Borra los registros de `catalog_books` e importa el catálogo desde cero.
No elimina el archivo SQLite ni otras tablas. Los libros importados reciben una
nueva `insertDate` y `lastModifiedDate` queda en `null`.

### Actualizar el catálogo

```sh
curl -i -X POST http://localhost:8080/api/catalog/import/update
```

Identifica cada libro por `eplId`. Inserta los nuevos y actualiza los existentes
solo si cambia algún dato del CSV. Conserva `insertDate` y establece
`lastModifiedDate` al modificar un libro. Los libros ausentes del CSV se conservan.

Ejemplo de respuesta de `/reset` y `/update`:

```json
{
  "success": true,
  "message": "Importación completada",
  "recordsProcessed": 100,
  "errors": 0,
  "recordsUpdated": 20,
  "recordsCreated": 10,
  "recordsUnchanged": 70
}
```

`recordsProcessed` es la suma de nuevos, modificados y sin cambios. `errors`
cuenta las filas omitidas por errores de conversión o campos obligatorios ausentes.
Los contadores se refieren a filas procesadas del CSV. Los `eplId` repetidos
se omiten y cuentan como errores, tanto en la importación como en la previsualización. Un error de persistencia o de lectura que aborte la importación revierte
la transacción completa, incluido el borrado del modo reemplazo.

### Previsualizar una actualización

```sh
curl -i -X POST http://localhost:8080/api/catalog/import/preview
```

Descarga y compara el CSV sin insertar, actualizar ni borrar libros. Por defecto
solo devuelve `success`, `message`, `recordsProcessed`, `errors`, `recordsUpdated`,
`recordsCreated` y `recordsUnchanged`, sin listas de libros.

Para consultar los libros completos desde un frontend, solicita explícitamente el detalle:

```sh
curl -i -X POST 'http://localhost:8080/api/catalog/import/preview?includeDetails=true&page=0&size=50'
```

Con `includeDetails=true` devuelve:

- `summary`: los mismos contadores de importación, calculados sobre todo el CSV.
- `page` y `size`: página desde cero y tamaño (predeterminado 50, máximo 500).
- `createdBooks`: libros nuevos completos de la página solicitada.
- `updatedBooks`: cambios de la página, con `before`, `after` y `changedFields`.

Las dos listas se paginan **por separado**, en orden de aparición en el CSV.
Por ejemplo, `page=1&size=50` devuelve los elementos 51–100 de cada lista.
Los totales de `summary.recordsCreated` y `summary.recordsUpdated` permiten
calcular las páginas; una página fuera del rango devuelve una lista vacía.

Los libros nuevos muestran fechas locales nulas. En `after`, las fechas locales
conservan sus valores actuales: las nuevas fechas se asignan al guardar realmente.
Los nombres de `changedFields` son los campos Java/JSON, como `title` o `revision`.

Cada petición vuelve a descargar y comparar el catálogo, también al cambiar de
página. No se guarda una instantánea ni un identificador para aplicar la vista:
si el CSV remoto o la BD cambian, una petición posterior puede devolver resultados
distintos. `/update` realiza una nueva descarga y comparación.

## Fecha de inserción local

El campo Java/JSON `insertDate` corresponde a la columna `insert_date` y conserva
la fecha de la primera inserción en la BD. `lastModifiedDate` mantiene su nombre
y solo cambia cuando se modifica el libro.

Se parte de una instalación nueva: Hibernate crea directamente la columna
`insert_date`. No se incluye una migración desde esquemas anteriores.

## Verificación del proyecto

Las pruebas usan SQLite en memoria y no modifican la base de datos local.
Para compilar, ejecutar todas las pruebas y generar el JAR:

```sh
./backend/mvnw -f backend/pom.xml clean verify
```

## Enlaces magnet

La API genera enlaces BitTorrent v1 a partir de `CatalogBook.links`, sin descargar
metadatos ni conectar con qBittorrent. Conserva los endpoints de libros existentes.

| Petición GET | Respuesta |
| --- | --- |
| `/api/catalog/books/{eplId}/magnets` | Array JSON de magnets del libro; `[]` si no tiene hashes válidos y `404` si no existe |
| `/api/catalog/magnets` | Array JSON de magnets de los libros filtrados |
| `/api/catalog/magnets?page=0&size=20` | `{ "items": [...], "meta": {...} }`, con la misma estructura de metadatos que la búsqueda de libros |
| `/api/catalog/magnets/export` | Archivo `magnets.txt`, `text/plain;charset=UTF-8`, con un magnet por línea |

La búsqueda y la exportación admiten los mismos filtros del catálogo (`author`,
`title`, `language`, fechas, estados, etc.) y `sort=title,asc`, por ejemplo.
El orden predeterminado es `eplId,asc`; también se usa `eplId` para desempatar.
La exportación devuelve todos los resultados filtrados, sin paginación.

```sh
curl 'http://localhost:8080/api/catalog/books/85202/magnets'
curl 'http://localhost:8080/api/catalog/magnets?author=Wells&page=0&size=20'
curl 'http://localhost:8080/api/catalog/magnets/export?author=Wells' -o magnets.txt
```

Se genera un magnet por hash distinto. Se aceptan hashes hexadecimales de 40
caracteres y Base32 de 32 caracteres; estos últimos se convierten a hexadecimal.
Los hashes del catálogo se separan por comas; también se admiten espacios,
saltos de línea y punto y coma. Los valores inválidos se omiten. La deduplicación
se hace por hash, incluso entre libros: se conserva el nombre del primer libro
según el orden solicitado.

Si se proporciona `page` o `size`, se pagina **después** de expandir y deduplicar
los hashes. Los totales cuentan magnets, no libros. Por defecto `page=0` y
`size=20`; `size` admite valores entre 1 y 500. Una página fuera del rango devuelve
`items: []`; parámetros de paginación inválidos devuelven `400`.
Para calcular totales exactos, esta primera versión procesa en memoria todos los
resultados filtrados, cargando únicamente `eplId`, `title` y `links` de la BD.

El nombre sugerido es `EPL_{eplId}_{title}`, codificado en UTF-8 en `dn`.
`dn` no garantiza el nombre permanente en qBittorrent ni renombra los archivos:
el cliente puede sustituirlo al recibir los metadatos. Para fijar el nombre
mostrado se necesita la operación de renombrado del cliente.
La URL HTTP de estos endpoints no es un archivo `.torrent`; se deben utilizar
los enlaces `magnet:` devueltos (por ejemplo, copiando el texto exportado en el
cuadro de añadir enlaces del cliente).

### Trackers y Docker

En `backend/src/main/resources/application.yaml` se configura la lista global:

```yaml
eplsync:
  torrent:
    trackers:
      - "udp://tracker.opentrackr.org:1337/announce"
```

`EPLSYNC_TORRENT_TRACKERS` sustituye **toda** la lista con URLs separadas por comas.
Cada URL genera un parámetro `tr`. Se eliminan duplicados y espacios exteriores.
Se admiten HTTP, HTTPS y UDP (este último requiere puerto). Una URL inválida impide
el arranque para detectar errores de configuración. Las direcciones no se prueban
contra la red: su configuración no garantiza que el tracker esté disponible.

Ejemplo del bloque a incorporar al futuro servicio de `docker-compose.yml`:

```yaml
services:
  eplsync:
    # Añadir aquí image o build del despliegue.
    environment:
      EPLSYNC_TORRENT_TRACKERS: "${EPLSYNC_TORRENT_TRACKERS-udp://tracker.opentrackr.org:1337/announce}"
```

En el `.env` situado junto a `docker-compose.yml`:

```dotenv
EPLSYNC_TORRENT_TRACKERS=udp://tracker.opentrackr.org:1337/announce,udp://www.torrent.eu.org:451/announce
```

Alternativamente, usar `env_file: .env` en el servicio en lugar del bloque
`environment`. Compose lee `.env` para interpolación; por sí solo no introduce
todas sus variables en el contenedor. Spring tampoco carga automáticamente un
archivo `.env` al ejecutarse fuera de Docker.

Un valor vacío (`EPLSYNC_TORRENT_TRACKERS=`) genera magnets sin `tr`, que el
cliente puede resolver mediante DHT si está habilitado y hay pares disponibles.
El ejemplo de Compose usa `${VARIABLE-defecto}`, sin `:`, para respetar ese valor
vacío. Los cambios requieren reiniciar la aplicación (o recrear el contenedor
con el nuevo entorno), pero no reimportar el catálogo ni modificar la BD.
