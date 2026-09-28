# EPL Sync

## Ejecutar con Docker

Desde la raíz del repositorio, con Docker y Docker Compose v2 o posterior,
prepara la configuración local (solo la primera vez):

```sh
cp .env.example .env
mkdir -p data logs
```

Edita `.env` antes de arrancar: ajusta la URL de qBittorrent y sustituye `api_key`
por tu clave real. Si no vas a usar la integración, establece
`EPLSYNC_TORRENT_ENABLED=false`. `.env.example` contiene valores de ejemplo;
Compose carga automáticamente `.env`, no `.env.example`.

Las carpetas `data` y `logs`, y sus archivos existentes, deben permitir escritura
al UID/GID configurado en `.env` mediante `PUID` y `PGID` (por defecto `1000:1000`).
En Linux, puedes consultar tu UID/GID con `id -u` e `id -g` y usar esos valores
si las carpetas te pertenecen. Si necesitas asignarlas a `1000:1000`:

```sh
sudo chown -R 1000:1000 data logs
```

Adapta esos números si has elegido otros. El `chown` del Dockerfile solo afecta
a la imagen; no cambia los permisos de las carpetas montadas desde el host.

```sh
docker compose up -d --build
docker compose logs -f eplsync
curl http://localhost:8088/actuator/health
```

La imagen compila el backend con el wrapper Maven y Java 25 en una etapa separada;
la ejecución usa un JRE 25 y un usuario sin privilegios, con el UID/GID indicado.
No requiere Java ni Maven instalados en el host. `PUID` y `PGID` se aplican durante
la construcción: si los cambias, ajusta los permisos y reconstruye con
`docker compose up -d --build`. El build omite los tests; para validarlos antes
puedes ejecutar `cd backend && ./mvnw verify`.

El puerto publicado por defecto es `8088` en todas las interfaces del host.
Puedes cambiarlo con `HOST_PORT` en `.env` o con
`HOST_PORT=8090 docker compose up -d`, manteniendo el 8088 interno. Ajusta también
el puerto de las peticiones de ejemplo. Esta API no incluye autenticación;
publica el servicio solo en una red de confianza o detrás de un proxy que controle
el acceso. Para limitarlo al host, cambia el mapeo de Compose a
`"127.0.0.1:${HOST_PORT:-8088}:8088"`.

SQLite y los jobs/historial se guardan en `./data/eplsync.db` del host, mediante
el montaje de `./data` en `/app/data`. Si la BD ya existe, el contenedor la utiliza;
si no existe, la aplicación la crea. Tanto `docker compose down` como
`docker compose down -v` conservan estas carpetas del host y sus datos.
No ejecutes dos instancias contra la misma base SQLite.

### Configuración y conexión con qBittorrent

`.env` está excluido de Git y del contexto de build. `.env.example` se versiona
como plantilla y también queda fuera del contexto de build.
Compose utiliza `.env` para interpolar el YAML; el bloque `environment` actual
pasa estas variables de la aplicación al contenedor:

- `EPLSYNC_CATALOG_ZIPURL`
- `EPLSYNC_TORRENT_ENABLED`
- `EPLSYNC_TORRENT_CLIENT`
- `EPLSYNC_TORRENT_BASEURL`
- `EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY`

También establece `TZ=Europe/Madrid`. Las demás variables de `.env.example`
son ejemplos para futuras ampliaciones y no se transmiten actualmente.
La autenticación usa el modo `auto` de `application.yaml`, que permite utilizar
la API key configurada. Para pasar otras opciones desde `.env`, añádelas primero
al bloque `environment` del servicio.

Los nombres de variables siguen el binding nativo de Spring: puntos por guiones
bajos y sin guiones dentro de los nombres (`base-url` → `BASEURL`,
`api-key` → `APIKEY`). `application.yaml` mantiene valores literales y no necesita
placeholders de entorno. No se utiliza `env_file` con `format: raw`; si un valor
de `.env` contiene un `$` literal, puedes encerrarlo entre comillas simples para
evitar su interpolación por Compose.

Tras modificar las variables transmitidas, ejecuta `docker compose up -d` para
recrear el contenedor. No cambies `SERVER_PORT` si mantienes el mapeo de puertos
del Compose incluido.

`localhost` dentro del contenedor es EPLsync. Para qBittorrent usa:

- En el host: `http://host.docker.internal:PUERTO_WEBUI`; en Linux puede requerir
  añadir `extra_hosts: ["host.docker.internal:host-gateway"]` al servicio.
- En otro contenedor de una red Docker compartida: `http://qbittorrent:PUERTO_WEBUI`,
  usando su nombre de servicio y puerto interno. Ambos servicios deben unirse a esa red.
- En otro equipo o detrás de un dominio: su URL accesible desde Docker.

No necesitas montar los archivos descargados por qBittorrent dentro de EPLsync.
El seguimiento consulta su API; los destinos de descarga pertenecen al cliente torrent.

También puedes montar un YAML privado como configuración externa ampliando el
bloque `volumes` del servicio (crea el archivo antes de arrancar):

```yaml
volumes:
  - ./data:/app/data
  - ./logs:/app/logs
  - ./config/application-local.yaml:/app/config/application.yaml:ro
```

Spring carga automáticamente `/app/config/application.yaml`. El archivo de origen
`application-local.yaml` ya está ignorado por Git. Las variables de entorno tienen
prioridad sobre este archivo. Los perfiles locales del código fuente no se copian
ni se empaquetan en la imagen Docker.

## Logs persistentes

Los logs se escriben en consola y en `./logs/eplsync.log`, relativo al directorio
desde el que arranca Java. En Docker, el Compose incluido monta `./logs` del host
en `/app/logs`; el usuario del contenedor debe poder escribir en ese directorio.
Si arrancas desde `backend`, la ruta local será `backend/logs/eplsync.log`.

El archivo rota al alcanzar 50 MB y al cambiar de día (en la siguiente escritura).
Los históricos se llaman `eplsync.20260928.0.log`, `eplsync.20260928.1.log`, etc.
La fecha corresponde al período del log según la zona horaria de la JVM y el
contador comienza en cero cada día. El umbral no es un límite estricto: una entrada
puede hacer que el archivo supere ligeramente los 50 MB.

Se conservan hasta 30 días de históricos, con un límite adicional de 1 GB para
los archivos rotados; el archivo activo no cuenta para ese límite. La limpieza
se realiza al arrancar y durante la rotación, de forma asíncrona. Los históricos
se guardan sin compresión y están excluidos de Git.

La configuración está en `backend/src/main/resources/application.yaml`. Puedes
ajustar la retención mediante `LOGGING_LOGBACK_ROLLINGPOLICY_MAXHISTORY` y
`LOGGING_LOGBACK_ROLLINGPOLICY_TOTALSIZECAP`. Si cambias la ubicación, ajusta tanto
`LOGGING_FILE_NAME` como `LOGGING_LOGBACK_ROLLINGPOLICY_FILENAMEPATTERN`.

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
- `page` y `size`: página desde cero y tamaño (predeterminado 50, sin máximo de aplicación).
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
`size=20`; `size` admite enteros positivos, sin máximo de aplicación. Una página fuera del rango devuelve
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

Ejemplo de variable a añadir al bloque `environment` del servicio en `docker-compose.yml`:

```yaml
services:
  eplsync:
    # Conservar el resto de la configuración del servicio.
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

La integración permite comprobar la conexión, enviar un libro por su EPL Id
y renombrar explícitamente torrents existentes. La integración está deshabilitada
por defecto y no conecta al arrancar; el catálogo funciona aunque qBittorrent
esté apagado. Las conexiones se realizan al solicitar una operación torrent.

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
`EPLSYNC_TORRENT_ENABLED=true`. Como alternativa al bloque `environment` actual, puedes configurar `env_file`
en el servicio Compose para transmitir todas las variables del archivo:

```yaml
services:
  eplsync:
    # Conservar build, puertos y volúmenes del servicio actual.
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
- Fechas se muestran como `YYYY-MM-DD`; `language` como su código (`es`, `en`, etc.) y los demás enums como su nombre.
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

### URL del ZIP del catálogo

La URL predeterminada para importar y previsualizar se configura en `application.yaml`
y puede sobrescribirse en `application-local.yaml`:

```yaml
eplsync:
  catalog:
    zip-url: "https://epublibre.org/rssweb/csv/epub.zip"
```

El parámetro opcional `url` de los endpoints de importación tiene prioridad sobre
este valor. En Docker puede sobrescribirse con `EPLSYNC_CATALOG_ZIPURL`.

### Enviar un libro a qBittorrent

`POST /api/torrent/books/{eplId}` envía un único torrent del libro.
No descarga el EPUB a EPLSync: qBittorrent recibe el magnet y gestiona la descarga.
El envío está diseñado para qBittorrent 5.2.3. El envío por búsqueda se describe en la sección bulk; el envío por lista explícita de IDs queda pendiente.

Sin cuerpo se usan los valores predeterminados de `application.yaml` (sobrescribibles en el perfil local):

```yaml
eplsync:
  torrent:
    download:
      start: true
      save-path: null
    rename:
      enabled: true
      pattern: "{author} - {title} [{eplId}] (r{revision})"
    qbittorrent:
      download:
        category: "Libros"
        tags: ["EPLSync", "{language}"]
        auto-management: true
```

La integración debe estar habilitada y autenticada. La categoría `Libros` debe existir en qBit;
EPLSync no crea categorías. Ejemplo con valores predeterminados:

```sh
curl -X POST http://localhost:8088/api/torrent/books/2663
```

Ejemplo con opciones personalizadas:

```sh
curl -X POST http://localhost:8088/api/torrent/books/2663 \
  -H 'Content-Type: application/json' \
  -d '{
    "start": false,
    "savePath": "/downloads/libros",
    "rename": {
      "enabled": true,
      "pattern": "EPL_{eplId}_{title}"
    },
    "qbittorrent": {
      "category": "Libros",
      "tags": ["EPLSync", "Pendientes"],
      "autoManagement": false
    }
  }'
```

- Cada campo omitido o `null` hereda su valor configurado, también dentro de los objetos.
- `tags: []` elimina las etiquetas predeterminadas; las listas personalizadas las sustituyen.
- `category: ""` añade sin categoría. Las etiquetas no admiten comas ni caracteres de control.
- `start: false` envía `stopped=true` a qBit; `true` permite iniciar respetando su cola.
- `autoManagement: true` usa gestión automática y no admite una ruta explícita.
- `autoManagement: false` permite `savePath`; sin ruta se utiliza la predeterminada de qBit.
  `savePath: ""` borra una ruta heredada. La ruta pertenece al equipo/contenedor de qBit.
- `rename.enabled: false` omite el nombre personalizado. Si está activo, se usan campos
  de `CatalogBook`; no se renombran archivos ni carpetas.
- Si existen varios hashes válidos en `links`, es obligatorio incluir `hash` en el JSON.
  Se admite hexadecimal o Base32 y debe pertenecer al libro. Solo se envía ese hash.

Respuesta `202 Accepted`:

```json
{
  "eplId": 2663,
  "hash": "0123456789ABCDEF0123456789ABCDEF01234567",
  "client": "qbittorrent",
  "status": "ACCEPTED"
}
```

`ACCEPTED` indica que qBit aceptó el envío, no que haya obtenido los metadatos o terminado
la descarga. No se crea un trabajo en segundo plano en EPLSync en esta primera versión.
Si el hash ya existe, devuelve `200` y `ALREADY_EXISTS`, conservando nombre, categoría,
etiquetas y estado existentes. La comprobación y el alta no son atómicas frente a otros clientes.

Errores: `400` para opciones/hash inválidos, `404` para libro inexistente, `422` para libro
sin hashes válidos, `409` para integración deshabilitada, selección de hash necesaria,
categoría inexistente o rechazo de qBit; `502`/`503`/`504` para comunicación/autenticación.
Solo se realiza un POST de alta: ante un timeout el resultado puede ser incierto y no se
reenvía automáticamente. Comprueba qBit antes de repetir la petición.

Las propiedades específicas quedan en el adaptador qBittorrent; el contrato común permite
incorporar otros clientes sin añadir dependencias de protocolo a los controladores.

#### Placeholders en etiquetas

Los tags del YAML y de la petición admiten campos de `CatalogBook`, igual que el
renombrado: `["EPLSync", "{language}"]` produce `["EPLSync", "es"]` para un
libro en español. También se admiten prefijos, por ejemplo `"idioma:{language}"`.
Los campos se resuelven una sola vez, conservando literalmente sus valores.

Se rechazan campos desconocidos y llaves mal formadas antes de contactar con qBit.
Los campos nulos se convierten en texto vacío; se omiten los resultados vacíos,
se recortan espacios exteriores y se eliminan duplicados conservando el orden.
Cada entrada representa una etiqueta: los resultados con comas o caracteres de
control se rechazan, también si proceden de campos como `genres` o `author`.
Las etiquetas de la petición sustituyen la lista del YAML; `[]` envía sin etiquetas.
qBittorrent crea las etiquetas nuevas al añadir el torrent. Un torrent ya existente
conserva sus etiquetas.

### Idioma en la API

`language` se devuelve como el código de `Language.getIsoCode()` (`es`, `en`, `ca`, etc.).
`OTRO` se representa como `other`, un valor propio de la aplicación, no un código ISO 639-1.
Los filtros aceptan `language=es` y conservan compatibilidad con `language=ESPANOL`.
Los valores no reconocidos se rechazan; la importación CSV mantiene su conversión habitual.
El placeholder `{language}` también produce el código en nombres y etiquetas torrent.
Los valores almacenados en la base de datos no cambian; no hace falta reimportar.

### Envío bulk por búsqueda

`POST /api/torrent/books` usa los filtros de `GET /api/catalog/books` y crea un
trabajo persistente. Cada elemento envía un único torrent. La selección se guarda
antes de responder `202 Accepted`; no se espera al envío ni a las descargas. La
preparación de una selección grande puede tardar y ocupa una transacción de SQLite.

- Con `page` o `size`, selecciona únicamente esa página (índice desde cero;
  valores por defecto de paginación: página 0 y tamaño 20).
- Sin ambos, selecciona todos los libros que cumplen los filtros, recorriéndolos
  en lotes; no carga todo el catálogo en memoria.
- Sin filtros ni paginación requiere `all=true` de forma explícita.
- Los parámetros desconocidos se rechazan con `400` (por ejemplo, `pages`).
- Se ordena por `eplId` si no hay `sort`; con otra ordenación se añade `eplId`
  como desempate. La selección y los datos del libro quedan congelados en el trabajo.

Ejemplo: todos los libros en inglés con opciones y ejecución personalizadas:

```sh
curl -X POST 'http://localhost:8088/api/torrent/books?language=en' \
  -H 'Content-Type: application/json' \
  -d '{
    "options": {
      "start": false,
      "qbittorrent": {
        "category": "Libros",
        "tags": ["EPLsync", "{language}"]
      }
    },
    "batchSize": 100,
    "concurrency": 2,
    "interval": "500ms"
  }'
```

Ejemplo para enviar solo la segunda página de 50 resultados con los valores por defecto:

```sh
curl -X POST 'http://localhost:8088/api/torrent/books?author=Brandon&page=1&size=50'
```

Configuración predeterminada:

```yaml
eplsync:
  torrent:
    bulk:
      batch-size: 100
      concurrency: 1
      interval: 500ms
      multiple-hashes: skip
```

Los campos omitidos o `null` del cuerpo heredan individualmente esos valores.
También se acepta `batch-size` como alias JSON de `batchSize`. `interval` admite
valores como `500ms`, `2s` o duraciones ISO-8601. `options` usa las mismas opciones
que el endpoint individual salvo `hash`, que no se admite en bulk.

- `batchSize`: 1–1000 elementos cargados por lote desde SQLite. No es el número
  de magnets en una petición a qBit: se sigue enviando uno por petición.
- `concurrency`: 1–16 operaciones de envío simultáneas.
- `interval`: 0ms–60s de separación mínima entre el inicio de operaciones,
  global para el trabajo, no por hilo. El coordinador espera hasta el próximo
  envío permitido y se despierta al finalizar una operación; no añade una pausa
  fija de 100ms entre pasadas. No acumula envíos atrasados para lanzarlos en ráfaga.
  La separación real puede ser mayor por la red, la persistencia o la carga.
  Los trabajos nuevos o reanudados se detectan mediante una comprobación cada 250ms
  como máximo mientras el coordinador está esperando.
- `size` de selección: entero positivo, sin máximo de aplicación e independiente de `batchSize`.

Se ejecuta un trabajo a la vez; los demás esperan. La concurrencia se aplica
al trabajo activo. Los valores efectivos de descarga, trackers, nombres y datos
para resolver tags se guardan por elemento. Cambiar el catálogo o los valores
predeterminados del YAML no altera los elementos pendientes. Las credenciales
no se guardan en las tablas de trabajos. Si cambia el cliente o su URL base,
el trabajo se pausa y exige restaurar el destino original para reanudar.

El `202` incluye `jobId`, `status`, `selectedBooks`, contadores y los valores
`batchSize`, `concurrency` e `interval` efectivos, además de una cabecera
`Location: /api/torrent/jobs/{jobId}`. Consulta y control:

```http
GET  /api/torrent/jobs/{jobId}
GET  /api/torrent/jobs/{jobId}/items?page=0&size=50
POST /api/torrent/jobs/{jobId}/pause
POST /api/torrent/jobs/{jobId}/resume
POST /api/torrent/jobs/{jobId}/cancel
```

La respuesta de progreso incluye `accepted`, `alreadyExists`, `skipped`, `failed`,
`pending`, `inFlight`, `cancelled`, `processedBooks` y la causa de pausa/error.
`processedBooks` cuenta libros con todos sus elementos procesados (sin pendientes,
en curso ni cancelados). `processedItems` suma aceptados, existentes, omitidos y
fallidos; los cancelados se cuentan aparte. El detalle paginado no expone snapshots internos ni credenciales.

Estados del trabajo: `QUEUED`, `RUNNING`, `RETRY_WAIT`, `PAUSED`, `COMPLETED`, `CANCELLED`.
Un trabajo completado puede contener errores: revisa sus contadores y elementos.

- Hashes repetidos dentro del trabajo y libros sin hashes válidos se omiten con
  explicación. Los libros con varios hashes siguen la política `multipleHashes`.
- Antes de cada alta se consulta si el hash existe; en ese caso se conserva el
  torrent sin modificarlo y se registra `ALREADY_EXISTS`.
- Un error específico del elemento se registra y el trabajo continúa.
- La autenticación rechazada o incompleta pausa el trabajo para reanudación manual.
- Fallos de red, respuesta remota inválida o timeout pausan los nuevos envíos y
  programan como máximo tres reintentos automáticos, tras 30, 60 y 120 segundos.
  Después se requiere reanudar manualmente. Cada reintento vuelve a consultar el
  hash antes de añadirlo; un timeout puede haber ocurrido después de que qBit lo aceptase.
- Pausa/cancelación impiden nuevos envíos. Las operaciones en curso pueden terminar;
  cancelar no borra ni detiene torrents ya enviados a qBit.
- Tras reiniciar EPLSync, los trabajos en ejecución se recuperan y los elementos
  en curso vuelven a comprobarse por hash. Los trabajos pausados siguen pausados
  y los cancelados no se reenvían.

Persistencia en `torrent_bulk_jobs` y `torrent_bulk_items`, independientes del
catálogo; un reset del catálogo no elimina la selección guardada. Se utiliza la
gestión de esquema Hibernate ya configurada (`ddl-auto: update`). La cola está
pensada para **una instancia de EPLSync por base de datos**; no incluye coordinación
entre varias réplicas. La comprobación de duplicados y el alta no son atómicas
frente a otros clientes que operen sobre qBit.

La concurrencia e intervalo regulan las peticiones de EPLSync. El número de
descargas activas y el consumo de recursos de los torrents añadidos dependen de
qBittorrent. `options.start: false` permite añadirlos detenidos.

#### Política de libros con varios hashes

En `eplsync.torrent.bulk.multiple-hashes` se configura el valor predeterminado
(`skip`). La petición puede sobrescribirlo mediante `multipleHashes`:

```sh
curl -X POST 'http://localhost:8088/api/torrent/books?language=en' \
  -H 'Content-Type: application/json' \
  -d '{"multipleHashes":"all","options":{"start":false}}'
```

- `all`: un elemento por cada hash válido del libro, en el orden de `links`.
- `skip`: omite los libros con más de un hash válido distinto.
- `first`: utiliza el primer hash válido de `links`, sin ordenar ni elegir por versión.

Los hashes se normalizan y deduplican. Un hash repetido entre libros se registra
como omitido y se envía una sola vez por trabajo. La política se guarda al crear
el trabajo: los existentes conservan su selección y los antiguos se interpretan
como `skip`. El endpoint individual conserva su selección explícita de hash.

`selectedBooks` cuenta libros; `selectedTorrents` cuenta hashes distintos
seleccionados, y `processedTorrents` los que ya no tienen elementos pendientes,
en curso o cancelados. `selectedItems` cuenta todas las filas del trabajo,
incluidos registros omitidos sin hash y duplicados. Los contadores `accepted`,
`alreadyExists`, `skipped`, `failed`, `pending`, `inFlight`, `cancelled` y
`processedItems` corresponden a esas filas. Un libro puede producir varios
resultados y no se considera procesado hasta resolverlos todos.

Cada hash recibe las mismas opciones del libro y el patrón de nombre configurado;
varios torrents del mismo libro pueden tener el mismo nombre mostrado.
Cancelar el trabajo detiene los envíos pendientes, sin eliminar torrents ya enviados.

#### Consultas y logs de los envíos

El adaptador reutiliza en memoria el método de autenticación seleccionado para
los envíos individuales y bulk. Las versiones se consultan al preparar ese estado,
no por cada torrent. El endpoint explícito de comprobación de conexión sigue
consultando las versiones al llamarlo.

En un trabajo bulk, tras las consultas iniciales, cada torrent nuevo necesita
`torrents/info` y `torrents/add`; uno existente solo necesita `torrents/info`.
Las categorías se consultan una sola vez por contexto, cuando se necesita validar
la categoría del primer torrent nuevo. No se consultan si no hay categoría o todos
los torrents ya existen.

Cada petición individual tiene su propio contexto. Los elementos concurrentes de
un trabajo bulk comparten el suyo, sin TTL y sin compartir categorías con otras
peticiones o trabajos. Al pausar y reanudar, reintentar un trabajo interrumpido o
recuperarlo tras reiniciar, se crea un contexto nuevo y se vuelven a consultar.
Si cambia una categoría en qBit durante una ejecución, la lista de ese contexto
no se refresca automáticamente. La autenticación sigue recuperándose por separado.

Un `401/403` en una lectura provoca recuperación de autenticación y un único
reintento de esa lectura. El POST de alta nunca se repite automáticamente dentro
del adaptador: un rechazo de autenticación invalida el estado para la siguiente
operación. Los reintentos del trabajador siguen consultando primero el hash.

Logs torrent:

- `INFO`: petición individual con `eplId`, selección bulk
  con filtros/paginación, creación del job y sus parámetros efectivos, ejecución,
  finalización y controles de pausa/reanudación/cancelación.
- `WARN`: fallos individuales y bulk con identificadores y motivo; interrupciones y reintentos.
- `DEBUG`: preparación de autenticación y actualización de categorías.
- `TRACE`: inicio y resultado por torrent, además del método, ruta API, estado HTTP
  y duración de las llamadas a qBit. Los cambios de estado del job incluyen un
  resumen de contadores en `INFO`.

No se registran claves, contraseñas, cookies, cabeceras de autenticación, magnets,
cuerpos HTTP ni snapshots de libros. Los filtros de búsqueda se registran en
`INFO`; se eliminan saltos de línea y se limita la longitud del texto.

Con la configuración actual, `root: INFO` es el valor heredado; el paquete
`com.rlibanez.eplsync: DEBUG` lo sobrescribe para nuestras clases, mostrando
DEBUG, INFO, WARN y ERROR. `TRACE` queda oculto. El logger específico
`org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver: ERROR`
solo muestra ERROR; no afecta al `GlobalExceptionHandler` de EPLSync.
Con `com.rlibanez.eplsync: DEBUG` los envíos correctos por elemento quedan ocultos;
los fallos siguen siendo visibles en `WARN`. Activa `TRACE` para investigar envíos concretos.

### Historial de descargas y sincronización manual

Los envíos individuales y bulk registran cada torrent en `torrent_downloads`.
No se añaden columnas de descarga a `catalog_books` ni se borra el historial al
reimportar el catálogo. Se conserva la revisión del snapshot enviado, aunque el
catálogo cambie durante la descarga.

Estados iniciales:

- `SUBMITTED`: el cliente aceptó el envío; no implica descarga completada.
- `ALREADY_EXISTS`: el hash ya estaba en el cliente, pendiente de sincronizar su estado.
- `ERROR`: rechazo o fallo confirmado del envío.
- `UNKNOWN`: resultado incierto, por ejemplo un timeout o una interrupción. Antes
  de realizar la llamada se persiste este estado para poder reconciliar un cierre
  inesperado de EPLSync. No se reenvía desde el endpoint de sincronización.

Los elementos bulk omitidos sin enviar un hash no crean registros de descarga.
La identidad es instancia del cliente + libro + hash. Cada archivo/revisión con
un hash distinto conserva su registro. Reenviar el mismo hash no duplica el registro
ni cambia su revisión original, origen o evidencia de finalización.

```bash
curl -X POST 'http://localhost:8088/api/torrent/downloads/sync'
curl 'http://localhost:8088/api/torrent/downloads?eplId=32&page=0&size=20&sort=revision,desc'
```

La sincronización es exclusivamente manual. Consulta una instantánea completa
mediante `GET /api/v2/torrents/info`, sin filtros de categoría, y:

- Actualiza registros conocidos por hash, incluidos los ya completados. En qBittorrent
  compara `hash`, `infohash_v1` e `infohash_v2`: un torrent híbrido cuenta una sola
  vez en `remoteTorrents` e `ignored`. Conserva el hash original del registro; un
  falso `NOT_FOUND` por esta diferencia se corrige al repetir el sync.
- Descubre torrents cuyos hashes coincidan con `links` del catálogo actual.
- Ignora torrents que no coinciden con el historial ni con el catálogo.
- Traduce estados a `QUEUED`, `DOWNLOADING`, `PAUSED`, `CHECKING`, `DOWNLOADED`,
  `ERROR` o `UNKNOWN`. Un torrent detenido no se considera completo por estar detenido.
- Marca `NOT_FOUND` cuando desaparece un torrent previamente aceptado/observado.
  Un intento rechazado o incierto nunca observado conserva `ERROR`/`UNKNOWN` si
  sigue ausente. Si reaparece, vuelve a reflejar el estado del cliente.
- Conserva `completedAt` aunque el torrent desaparezca. Esto acredita una descarga
  histórica; no garantiza que el archivo siga en disco. Si se completó y eliminó
  entre consultas sin que lo observásemos, no se inventa una finalización.

Una respuesta inválida o un fallo de conexión no modifica estados ni fechas. La
reconciliación se aplica en una transacción. No añade torrents, renombra archivos
ni modifica qBittorrent. Mientras hay envíos en curso u otra sincronización, devuelve
`409`; se puede repetir después. Los envíos que lleguen durante una sincronización
esperan a que termine. La coordinación presupone una instancia de EPLSync por BD.

La respuesta incluye `client`, `clientInstanceId`, `remoteTorrents`, `checked`,
`created`, `updated` (cambio de estado o evidencia de finalización), `completed`
(nuevas finalizaciones registradas), `notFound`, `ignored` (torrents remotos ajenos)
y `checkedAt`. Son contadores de esta sincronización, no del job de envío.

Cada registro contiene `id`, `eplId`, `revision`, `hash`, `client`,
`clientInstanceId`, `status`, `origin`, `createdAt`, `requestedAt`, `submittedAt`,
`discoveredAt`, `completedAt`, `lastCheckedAt`, `lastSeenAt` y `lastError`.

- `origin`: `EPLSYNC` o `DISCOVERED`, inmutable.
- `requestedAt`: primer intento de envío desde EPLSync; `submittedAt`: primera
  aceptación confirmada. Pueden ser nulos en registros descubiertos.
- `discoveredAt`: detección de un registro añadido manualmente al cliente.
- `completedAt`: fecha indicada por el cliente o, si no la facilita, fecha de la
  primera observación de descarga completa.
- `lastCheckedAt`: última comprobación correcta, incluso si estaba ausente;
  `lastSeenAt`: última vez que constaba presente.
- La instancia se identifica mediante SHA-256 del tipo y URL base (sin barra final).
  Cambiar la URL identifica otra instancia; no se actualizan registros de la anterior.
  No se exponen credenciales ni URL de conexión en las respuestas.

#### Filtros de descargas

`GET /api/torrent/downloads` siempre devuelve `items` y `meta`, con paginación.

| Parámetro | Uso |
| --- | --- |
| `eplId`, `hash`, `revision` | Coincidencia exacta; hash sin distinguir mayúsculas |
| `status` | Uno o varios estados separados por comas |
| `origin` | `EPLSYNC`, `DISCOVERED` o ambos separados por comas |
| `client`, `clientInstanceId` | Tipo e instancia del cliente |
| `completed` | `true` si existe `completedAt`, incluso en `NOT_FOUND`; `false` en otro caso |
| `createdAtFrom` / `createdAtTo` | Intervalo inclusivo ISO 8601 con zona horaria |
| `requestedAtFrom` / `requestedAtTo` | Intervalo de primer intento |
| `submittedAtFrom` / `submittedAtTo` | Intervalo de primera aceptación |
| `completedAtFrom` / `completedAtTo` | Intervalo de finalización |
| `discoveredAtFrom` / `discoveredAtTo` | Intervalo de descubrimiento |
| `lastCheckedAtFrom` / `lastCheckedAtTo` | Intervalo de comprobación |
| `lastSeenAtFrom` / `lastSeenAtTo` | Intervalo de presencia |
| `page`, `size` | Desde 0; tamaño por defecto 20, sin máximo de aplicación |
| `sort` | Por defecto `createdAt,desc`; repetible para varios criterios |

Los filtros diferentes se combinan con AND; los estados/orígenes de una lista con
OR. Las fechas nulas no satisfacen filtros de intervalo. Parámetros desconocidos,
valores inválidos e intervalos invertidos devuelven `400`. Se admite ordenar por
identificadores, revisión, hash, estado, origen, cliente o cualquiera de las fechas;
se añade `id` para estabilizar el orden.

#### Resumen en el catálogo

Tanto `GET /api/catalog/books/{eplId}` como el listado añaden `download.items`,
ordenado por revisión y fecha de creación descendentes. Sin registros, `items: []`.
Se mantienen todos los campos anteriores del libro y no se consulta al cliente
para responder. Los datos reflejan el último envío/sincronización guardado.

```json
{
  "download": {
    "items": [
      {"id": "uuid-2", "revision": 2.0, "status": "DOWNLOADING", "completed": false},
      {"id": "uuid-1", "revision": 1.5, "status": "NOT_FOUND", "completed": true}
    ]
  }
}
```

`completed` se calcula a partir de `completedAt`. El detalle con cliente, hash y
fechas está en `/api/torrent/downloads?eplId=32`. El resumen usa consultas agrupadas
(hasta 500 libros por consulta), sin una consulta adicional por cada libro.

La primera sincronización también permite incorporar torrents anteriores a esta
funcionalidad, con origen `DISCOVERED`. Solo puede asociar revisiones antiguas si
ya estaban registradas: no se deduce una revisión del nombre del archivo ni se
reconstruyen hashes históricos que ya no estén en el catálogo.

### Listar jobs actuales y pasados

```sh
curl -s 'http://localhost:8088/api/torrent/jobs' | jq
curl -s 'http://localhost:8088/api/torrent/jobs?page=1&size=20' | jq
curl -s 'http://localhost:8088/api/torrent/jobs?status=QUEUED,RUNNING,RETRY_WAIT,PAUSED' | jq
curl -s 'http://localhost:8088/api/torrent/jobs?status=COMPLETED,CANCELLED' | jq
```

`GET /api/torrent/jobs` incluye todos los estados por defecto y devuelve `items`
y `meta`. Cada elemento contiene la misma información y contadores que
`GET /api/torrent/jobs/{jobId}`. No requiere conocer los IDs previamente.

La paginación comienza en `page=0`, con `size=20` por defecto (sin máximo de aplicación).
El orden es fecha de creación descendente, con ID descendente como desempate.
`status` admite uno o varios estados separados por comas. Una página sin resultados
contiene `items: []`; parámetros desconocidos o inválidos devuelven `400`.
El historial corresponde a los jobs persistidos en la base de datos actual y
sobrevive a los reinicios. Los envíos individuales no crean jobs bulk.

### Filtrar elementos de un job

El endpoint existente `GET /api/torrent/jobs/{jobId}/items` admite `status`:

```sh
curl -s 'http://localhost:8088/api/torrent/jobs/ID/items?status=SKIPPED&page=0&size=500' | jq
curl -s 'http://localhost:8088/api/torrent/jobs/ID/items?status=SKIPPED,FAILED' | jq
```

El filtro se aplica en la base de datos antes de paginar: `meta.totalItems` y
`meta.totalPages` corresponden a los elementos filtrados. El motivo de cada omisión
o fallo sigue disponible en `items[].message`. Sin `status` se incluyen todos.
Los estados admitidos son `PENDING`, `IN_FLIGHT`, `ACCEPTED`, `ALREADY_EXISTS`,
`SKIPPED`, `FAILED` y `CANCELLED`, separados por comas si se indican varios.
Se mantiene el orden de selección, `page=0`, `size=50` por defecto y sin máximo de aplicación.
Los filtros desconocidos, vacíos o inválidos devuelven `400`.

### Tamaño de página

Los endpoints de catálogo y torrent aceptan `page >= 0` y `size > 0`, sin límites
artificiales de 100, 500 o 2000 elementos. Spring tampoco recorta `size` a 2000.
Los parámetros usan enteros de 32 bits (máximo representable: 2147483647).
Los valores por defecto y el comportamiento sin paginación se mantienen.

Por ejemplo, `POST /api/torrent/books?language=es&page=0&size=10000&sort=eplId,asc`
selecciona hasta 10000 libros; no inicia 10000 envíos simultáneos. `batchSize`,
`concurrency` e `interval` siguen controlando la ejecución. En listados GET, una
página grande genera una respuesta mayor y requiere más memoria y tiempo.

### Actualizar revisiones de libros gestionados

La selección compara el catálogo actual con las descargas del **cliente y URL
configurados**. Incluye libros con una revisión superior a la mayor revisión
presente o enviada y con hashes válidos distintos. No incluye libros desconocidos,
revisiones iguales/inferiores, ni libros con envíos pendientes (incluidos jobs
pausados). Una revisión nueva ya enviada/presente no se vuelve a encolar. Un envío
incierto de esa revisión (`UNKNOWN`) requiere reconciliar su estado antes de
volver a seleccionarlo. Los intentos `ERROR` se pueden volver a seleccionar.

Previsualización de solo lectura: no consulta qBittorrent, no sincroniza y no
escribe registros. Usa el último estado guardado; si se necesita información
actual del cliente, ejecutar antes `POST /api/torrent/downloads/sync`.

```bash
curl -s 'http://localhost:8088/api/torrent/updates?page=0&size=50' | jq
curl -s 'http://localhost:8088/api/torrent/updates?eplId=1234&multipleHashes=all' | jq
```

La respuesta contiene `items` y `meta`. Cada candidato incluye `eplId`, `title`,
`catalogRevision`, `existingDownloads` (ID, revisión, hash y estado anteriores)
y `targetHashes`. `includeNotFound=true` permite actualizar también libros cuyo
único historial elegible ya no está en el cliente. `ERROR` y `UNKNOWN` no bastan
por sí solos para considerar un libro gestionado. `multipleHashes` acepta
`all`, `first` o `skip`; si falta, utiliza el valor bulk de `application.yaml`.

Crear el job (sin `eplId` selecciona **todos los candidatos**, no solo la página
mostrada en la previsualización):

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates?eplId=1234' \
  -H 'Content-Type: application/json' \
  -d '{"previousVersions":"keep","multipleHashes":"all","concurrency":2,"interval":"100ms"}' | jq
```

Devuelve `202`, el job bulk y `Location: /api/torrent/jobs/{jobId}`. El cuerpo es
opcional y admite `options` (como el envío individual, excepto `hash`),
`batchSize`/`batch-size`, `concurrency`, `interval` y `multipleHashes`. Los valores
omitidos heredan los defaults de descarga y bulk. Se mantienen los endpoints de
progreso, items, pausa, reanudación y cancelación de jobs. Si no hay candidatos,
se crea un job vacío ya completado. Repetir la petición no encola nuevamente
libros con una actualización pendiente. El catálogo, revisión, hashes y opciones
quedan congelados en el job; una importación posterior no cambia sus objetivos.

`previousVersions` se guarda en el plan y **no elimina nada durante el envío**:

| Valor | Limpieza posterior |
| --- | --- |
| `keep` (por defecto) | Conserva torrents y archivos anteriores. |
| `removeTorrent` | Elimina solo los torrents anteriores. |
| `removeTorrentAndFiles` | Solicita a qBittorrent eliminar torrents y sus archivos. |

Consultar el plan y ejecutar su limpieza por separado:

```bash
curl -s 'http://localhost:8088/api/torrent/updates/JOB_ID' | jq
curl -s -X POST 'http://localhost:8088/api/torrent/updates/JOB_ID/cleanup' | jq
```

El GET muestra la política, los objetivos congelados y el estado por registro
anterior. El POST consulta el estado **actual** de qBittorrent: antes de borrar,
todos los hashes seleccionados de la nueva revisión deben estar completos.
Identifica torrents híbridos por sus aliases y usa el ID remoto al borrar.
Solo considera versiones anteriores guardadas en el plan; nunca borra por
nombre o categoría. Conserva el historial y las fechas de descarga en EPLsync.

Estados de limpieza:

- `KEPT`: política de conservación; no hay borrado ni consulta al cliente.
- `WAITING`: falta la nueva revisión o todavía no está completa.
- `BLOCKED`: torrent compartido con otro libro, identidad coincidente con la
  nueva revisión o rutas de archivos compartidas/no verificables.
- `REQUESTED`: eliminación solicitada pero todavía no se ha confirmado que el
  torrent haya desaparecido. También se usa ante respuestas de red inciertas.
- `REMOVED`: ausencia confirmada; el historial anterior queda `NOT_FOUND`.

La limpieza usa una instantánea remota y otra para confirmar los borrados, no
una consulta de estado por libro. Al repetirla reevalúa `WAITING` y `BLOCKED`,
y comprueba los `REQUESTED` sin repetir automáticamente la escritura. Para
reintentar explícitamente una eliminación sin confirmar:

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates/JOB_ID/cleanup?retryUnconfirmed=true' | jq
```

Este reintento vuelve a comprobar completitud y protecciones. El sync habitual
nunca ejecuta limpiezas. Si hay envíos o sync en curso, la limpieza devuelve
`409`; si se cambió el destino, exige restaurarlo. La coordinación es por
instancia de EPLsync (una instancia por base de datos).

Para borrar archivos se comparan las rutas `content_path` reportadas por el
cliente: solo rutas POSIX absolutas, sin componentes `..`, y sin coincidencias
ni solapamientos con otros torrents. Si una ruta no se puede comprobar, se bloquea
el borrado de datos. Esta comprobación no inspecciona enlaces simbólicos o hard
links del sistema de archivos remoto, ni puede evitar cambios simultáneos hechos
fuera de EPLsync. `REMOVED` confirma la ausencia del torrent, no una verificación
física del borrado del archivo; el tratamiento final de los datos depende de
qBittorrent. No hay limpieza automática ni cambios de política implícitos.

### Resumen de descargas por estado

`GET /api/torrent/downloads/summary` devuelve `total` y `byStatus`, con todos los
estados (incluidos los que tienen contador cero). Cuenta registros de descarga,
no libros ni torrents únicos: varias revisiones de un libro son varios registros.

```bash
curl -s 'http://localhost:8088/api/torrent/downloads/summary' | jq
curl -s 'http://localhost:8088/api/torrent/downloads/summary?eplId=1234&origin=EPLSYNC' | jq
```

Admite los mismos filtros del listado de descargas, incluidos `client`,
`clientInstanceId`, `status`, `completed` y los rangos de fechas. Sin filtros
incluye todos los registros de la base de datos, también los de otros destinos.
No admite `page`, `size` ni `sort`: agrega todo el conjunto filtrado en una sola
consulta SQL, sin cargar las entidades. `total` es la suma de `byStatus`.

Es una consulta de solo lectura sobre el último estado guardado en EPLsync; no
consulta qBittorrent ni ejecuta un sync. Para actualizar esos estados, ejecutar
antes `POST /api/torrent/downloads/sync`. El filtro `completed=true` conserva su
significado habitual (existe `completedAt`); puede incluir registros `NOT_FOUND`
que terminaron de descargarse anteriormente.

### Filtrar el catálogo por eplId

El filtro compartido `eplId` admite un identificador positivo y aplica coincidencia
exacta. Se combina mediante AND con los demás filtros y funciona en
`GET /api/catalog/books`, `GET /api/catalog/magnets` (también `/export`) y
`POST /api/torrent/books`.

```bash
curl -s 'http://localhost:8088/api/catalog/books?eplId=32' | jq
curl -s 'http://localhost:8088/api/catalog/books?eplId=32&size=20' | jq
```

La búsqueda devuelve un listado vacío si no hay coincidencias; la ruta individual
`GET /api/catalog/books/32` sigue devolviendo un objeto o `404` si no existe.
Un `eplId` no numérico, fuera del rango de `Long`, cero o negativo devuelve `400`.

### Limpieza global de actualizaciones

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates/cleanup' | jq
```

Procesa los planes con política `removeTorrent` o `removeTorrentAndFiles` y
registros `WAITING`, `BLOCKED` o `REQUESTED`. Excluye `KEPT` y `REMOVED`.
Reevalúa las condiciones de seguridad y comprueba ausencias para los borrados
sin confirmar. No repite estos últimos salvo petición explícita:

```bash
curl -s -X POST 'http://localhost:8088/api/torrent/updates/cleanup?retryUnconfirmed=true' | jq
```

Comparte una instantánea inicial del cliente entre todos los jobs, los índices
de hashes/rutas y, si se intentaron borrados, una única consulta final de
confirmación. Sin trabajos aplicables no consulta al cliente. La exclusión mutua
con envíos y sync cubre toda la operación; las políticas de los jobs no cambian.

La respuesta contiene `selectedJobs`, `failedJobs`, `checked`, `removed`,
`waiting`, `blocked`, `requested` y `jobs`. Cada elemento de `jobs` contiene
`jobId`, los mismos contadores de registros y `error` (nulo cuando no hay error).
Los contadores describen solo los registros pendientes seleccionados en esta
petición, no todo el historial ni torrents únicos. Un torrent compartido por
varios registros se borra como máximo una vez. `failedJobs` puede coincidir con
registros `requested`: una escritura incierta no se declara eliminada.

Un destino incompatible se informa en el resultado del job y no se toca.
Los errores de un job no impiden procesar los demás. Si falla la consulta inicial
al cliente, falla la petición sin realizar borrados. Si falla la confirmación
final, los registros quedan sin confirmar y la respuesta incluye el error.

La limpieza global protege los torrents que son objetivos de otros planes
seleccionados y bloquea políticas incompatibles sobre el mismo torrent. En una
cadena de revisiones, puede ser necesario repetir la limpieza una vez resueltos
los planes anteriores. Sigue disponible la limpieza individual:
`POST /api/torrent/updates/{jobId}/cleanup`.
