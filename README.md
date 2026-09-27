# EPL Sync

## Importación del catálogo

Los tres modos descargan el ZIP oficial de ePubLibre. Admiten el parámetro
opcional `url` para indicar otro ZIP mediante HTTP o HTTPS.

### Reemplazar el catálogo

```sh
curl -i -X POST http://localhost:8088/api/catalog/import/reset
```

Borra los registros de `catalog_books` e importa el catálogo desde cero.
No elimina el archivo SQLite ni otras tablas. Los libros importados reciben una
nueva `insertDate` y `lastModifiedDate` queda en `null`.

### Actualizar el catálogo

```sh
curl -i -X POST http://localhost:8088/api/catalog/import/update
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
curl -i -X POST http://localhost:8088/api/catalog/import/preview
```

Descarga y compara el CSV sin insertar, actualizar ni borrar libros. Por defecto
solo devuelve `success`, `message`, `recordsProcessed`, `errors`, `recordsUpdated`,
`recordsCreated` y `recordsUnchanged`, sin listas de libros.

Para consultar los libros completos desde un frontend, solicita explícitamente el detalle:

```sh
curl -i -X POST 'http://localhost:8088/api/catalog/import/preview?includeDetails=true&page=0&size=50'
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
curl 'http://localhost:8088/api/catalog/books/85202/magnets'
curl 'http://localhost:8088/api/catalog/magnets?author=Wells&page=0&size=20'
curl 'http://localhost:8088/api/catalog/magnets/export?author=Wells' -o magnets.txt
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

## Conexión con clientes torrent

Esta fase implementa configuración, autenticación y comprobación de conexión.
Permite renombrar explícitamente torrents existentes; todavía no añade descargas. La integración está deshabilitada
por defecto y no conecta al arrancar; el catálogo funciona aunque qBittorrent
esté apagado. Las conexiones se realizan al solicitar la comprobación.

### Configuración

Las propiedades comunes están en `eplsync.torrent` en `application.yaml`, con valores
literales y sin placeholders de entorno. Spring permite sobrescribirlas mediante
su binding nativo de variables de entorno, también desde Docker (los puntos pasan
a guiones bajos y los guiones se eliminan):

| Variable | Predeterminado | Función |
| --- | --- | --- |
| `EPLSYNC_TORRENT_ENABLED` | `false` | Activa la integración |
| `EPLSYNC_TORRENT_CLIENT` | `qbittorrent` | Adaptador seleccionado (actualmente solo qBittorrent) |
| `EPLSYNC_TORRENT_BASEURL` | `http://localhost:8080` | URL de la WebUI, incluyendo protocolo, puerto y posible ruta del proxy |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE` | `auto` | `auto`, `api-key` o `session` |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY` | Vacío | Clave configurada en qBittorrent (compatible con 5.2.3) |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME` | Vacío | Usuario de la WebUI |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_PASSWORD` | Vacío | Contraseña de la WebUI |
| `EPLSYNC_TORRENT_CONNECTTIMEOUT` | `5s` | Tiempo máximo para establecer la conexión |
| `EPLSYNC_TORRENT_REQUESTTIMEOUT` | `10s` | Tiempo máximo por petición HTTP, no por toda la comprobación |
| `EPLSYNC_TORRENT_RENAME_ENABLED` | `true` | Habilita el renombrado del nombre mostrado del torrent |
| `EPLSYNC_TORRENT_RENAME_PATTERN` | `EPL_{eplId}_{title}` | Patrón; admite los nombres de campo de `CatalogBook`, incluido `{revision}` |

Para qBittorrent, la URL base no incluye `/api/v2`: su adaptador lo añade, conservando la ruta base.
Por ejemplo, `https://torrent.example.com/qbit/` consulta
`https://torrent.example.com/qbit/api/v2/app/version`. Se admiten HTTP y HTTPS
con validación TLS normal. No se siguen redirecciones: debe configurarse la URL
final de la WebUI, no una página de login de un proxy.

La autenticación específica se configura en `eplsync.torrent.qbittorrent.auth`.
En su modo `auto`, se prueba primero la API key, si existe. Solo si la respuesta es un
rechazo de autenticación (`401`/`403`) se intenta sesión, siempre que estén
configurados usuario **y** contraseña. Si solo hay un método completo, se usa ese.
Los modos explícitos usan exclusivamente su método, aunque haya otras credenciales.
Un error de red, timeout, redirección, respuesta inesperada o fallo del servidor
no provoca cambio de método. Un rechazo también puede proceder de las restricciones
de acceso de qBittorrent o del proxy; no siempre significa contraseña incorrecta.

La API key se envía como Bearer. El modo sesión inicia sesión con las credenciales
de la WebUI y conserva en memoria las cookies recibidas, incluyendo los nombres
`QBT_SID_*` de 5.2.3 y `SID` de versiones anteriores. También admite login `204`
sin cuerpo y el antiguo `200 Ok.`. Cuando una sesión es rechazada, se realiza
como máximo un nuevo login por comprobación. Las cookies no se guardan en disco.
Las respuestas de nuestra API no exponen URL, credenciales, cookies ni cuerpos de
error remotos.

Si la integración está habilitada, una configuración inválida impide el arranque:
URL incorrecta, timeouts no positivos
o patrón de rename inválido. El patrón solo se valida cuando rename está habilitado.
Las credenciales incompletas o inválidas se validan al usar la integración, no al
arrancar: la aplicación y el catálogo siguen disponibles. La comprobación devuelve
`503` si falta configuración de autenticación, o `502` si el servidor rechaza las
credenciales. No se intenta conectar durante el arranque.
Cambiar la configuración requiere reiniciar la aplicación.

### Arquitectura de adaptadores

`TorrentClient` define el contrato de conexión y `TorrentClientService` selecciona
el adaptador mediante `eplsync.torrent.client`. El controlador, el resultado
`TorrentConnectionStatus` y `TorrentConnectionException` son comunes y no dependen
de qBittorrent. El endpoint público es `/api/torrent/client/connection`; sustituye
al endpoint inicial `/api/qbittorrent/connection`.

Las clases específicas están juntas en el paquete `qbittorrent`:
`QBittorrentClient`, `QBittorrentProperties` y `QBittorrentConnectionException`.
Solo se instancia ese adaptador cuando está seleccionado. La generación de magnets
y los trackers siguen funcionando con la conexión deshabilitada.

Para incorporar otro cliente se implementará `TorrentClient` con su identificador,
su configuración y su protocolo de autenticación. No hay adaptadores ficticios para
Transmission, Deluge o uTorrent: seleccionar uno aún no implementado con la
integración habilitada provoca un error de configuración al arrancar.
El renombrado usa un método del contrato común que por defecto rechaza la operación
con `422`. Solo los adaptadores que puedan cambiar el nombre mostrado sin modificar
archivos deben implementarlo; qBittorrent ya lo implementa.

Ejemplo de estructura (los trackers permanecen en `eplsync.torrent.trackers`):

```yaml
eplsync:
  torrent:
    enabled: false
    client: qbittorrent
    base-url: "http://localhost:8080"
    connect-timeout: 5s
    request-timeout: 10s
    rename:
      enabled: true
      pattern: "EPL_{eplId}_{title}"
    qbittorrent:
      auth:
        mode: auto
        api-key: ""
        username: ""
        password: ""
```

### Docker y ejecución local

Crear un archivo `.env` con las variables de conexión y activar
`EPLSYNC_TORRENT_ENABLED=true`. Incorporar al futuro servicio Compose:

```yaml
services:
  eplsync:
    # Añadir image o build del despliegue.
    env_file:
      - .env
```

Si ambos contenedores comparten red Docker, `qbittorrent` puede ser el nombre del
servicio y `8080` su puerto **interno**. También se puede usar una URL pública
HTTPS. `localhost` desde el contenedor apunta al propio contenedor de EPL Sync.
El archivo `.env` está excluido de Git; no incluir credenciales reales en YAML
versionado. Fuera de Docker se deben exportar las variables al proceso Java:
Spring no carga `.env` automáticamente. También se pueden montar secretos como
archivos mediante la configuración `configtree` de Spring.

### Comprobar la conexión

```sh
curl http://localhost:8088/api/torrent/client/connection
```

La comprobación consulta `app/version` y `app/webapiVersion`; en modo sesión también
puede llamar a `auth/login`. No añade, borra, inicia, detiene ni renombra torrents.
Una respuesta correcta (`200`, `Cache-Control: no-store`) tiene esta estructura:

```json
{
  "enabled": true,
  "connected": true,
  "client": "qbittorrent",
  "authMode": "api-key",
  "version": "v5.2.3",
  "apiVersion": "2.15.1"
}
```

Las versiones son las devueltas por el servidor. Deshabilitado devuelve `200`,
`enabled: false`, `connected: false`, `client` con el adaptador configurado y
`authMode`, `version`, `apiVersion` a `null`, sin tráfico
hacia qBittorrent. Los errores usan el formato `ErrorResponse` de la API:

- `502`: autenticación rechazada, error de conexión o respuesta remota inválida.
- `504`: timeout.
- `503`: configuración de autenticación incompleta/inválida o comprobación interrumpida.

Las pruebas usan un servidor HTTP local simulado y SQLite en memoria; no requieren
un qBittorrent real ni credenciales y no modifican descargas.


### Nombre del torrent a partir del libro

`eplsync.torrent.rename.pattern` utiliza los nombres de los campos Java de
`CatalogBook`, respetando mayúsculas y minúsculas. Se descubren automáticamente
sus campos de instancia con getter; no se mantiene una lista duplicada. Por ejemplo:

```yaml
rename:
  enabled: true
  pattern: "{author} - {title} [{eplId}] (r{revision})"
```

Con los datos correspondientes, genera:

```text
Bronte, Charlotte - Jane Eyre [2663] (r1.2)
```

Reglas de resolución:

- Corchetes, paréntesis, guiones y demás texto fuera de `{campo}` son literales.
- Campos nulos se sustituyen por texto vacío. La puntuación del patrón se conserva.
- Fechas se muestran como `YYYY-MM-DD`; enums como su nombre (`ESPANOL`, etc.).
- Números usan punto decimal, sin ceros decimales innecesarios: `1.2`, `2`, `1.5`.
- Los valores se insertan literalmente, sin volver a interpretar llaves, `$` o
  barras presentes en el título o autor. Los caracteres de control y separadores
  de línea se convierten en espacios; se eliminan espacios exteriores del resultado.
- Se rechazan campos inexistentes, llaves mal formadas y resultados vacíos.
  No se admiten expresiones, llamadas a métodos ni rutas como `{language.name}`.

Para aplicar el patrón a **un torrent ya existente** en el cliente seleccionado:

```http
POST /api/catalog/books/{eplId}/torrents/{hash}/rename
```

No requiere cuerpo. El hash debe pertenecer a `links` de ese libro. Se admiten
hexadecimal de 40 caracteres y Base32 de 32, normalizado a hexadecimal. La operación
es explícita por hash, también cuando el libro tiene varios torrents. Devuelve
`eplId`, `hash`, `name` y `client` únicamente cuando el cliente confirma el cambio.

qBittorrent recibe `POST /api/v2/torrents/rename` con `hash` y `name`. No se llama a
`renameFile` ni `renameFolder`: cambia únicamente la columna **Name**, conservando
los nombres y rutas de los archivos. Primero se comprueba la autenticación con
lecturas; la petición de renombrado no se reintenta automáticamente ante errores o
timeouts, ya que el servidor podría haber aplicado el cambio.

- `400`: hash inválido, no pertenece al libro o el patrón produce un nombre vacío.
- `404`: libro ausente del catálogo o torrent ausente de qBittorrent.
- `409`: conexión/rename deshabilitados o nombre rechazado por qBittorrent.
- `422`: el adaptador no implementa renombrado exclusivo del nombre del torrent.
- `502`/`504`/`503`: mismos errores de comunicación que en la comprobación de conexión.

Cambiar el patrón no modifica descargas automáticamente. Los endpoints de magnets
mantienen su `dn` actual; ese nombre sugerido es independiente del nombre aplicado
al cliente por esta operación.
