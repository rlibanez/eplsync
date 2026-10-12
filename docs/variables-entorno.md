# Variables de instalación

[Instalación y despliegue](instalacion.md) · [Inicio](../README.md)

`.env.example` es la plantilla de configuración de `docker-compose.yml`.
Guárdala como `.env` en la misma carpeta y revisa los valores necesarios para tu
instalación. El resto puede conservar sus valores predeterminados.

```sh
docker compose up -d
```

Compose utiliza las variables de imagen, usuario, puerto y montajes para crear el
contenedor, y transmite las opciones de la aplicación mediante `environment`.
La tabla muestra los valores de `.env.example`, alineados con los valores de
reserva del Compose y los valores predeterminados de la aplicación.

## Variables

Ninguna variable exige un valor personalizado para arrancar la aplicación y utilizar el catálogo. El primer administrador se crea desde el asistente web. Las credenciales de qBittorrent solo son necesarias si activas la integración y configuras su autenticación desde `.env`; también puedes configurarlas desde Ajustes. Las condiciones de cada credencial se indican en la descripción.

| Variable | Descripción | Valor en `.env.example` |
| --- | --- | --- |
| `EPLSYNC_VERSION` | Versión de la imagen de EPL Sync que descargará Docker Compose. Cambiarla antes de ejecutar `docker compose pull` y `docker compose up -d`. | `1.1.2` |
| `PUID` | UID del usuario del contenedor; se aplica al ejecutarlo. Debe poder escribir en los montajes. | `1000` |
| `PGID` | GID del usuario del contenedor; se aplica al ejecutarlo. | `1000` |
| `TZ` | Zona horaria del contenedor. | `Europe/Madrid` |
| `HOST_PORT` | Puerto publicado en el host; el puerto interno sigue siendo 8088. | `8088` |
| `DATA_DIR` | Carpeta del host para SQLite, historial y datos persistentes; excluye la clave de cifrado, suministrada mediante EPLSYNC_SECRET_KEY. | `./data` |
| `LOGS_DIR` | Carpeta del host para los logs. | `./logs` |
| `EPLSYNC_SECRET_KEY` | Necesaria para guardar contraseñas/API keys de qBittorrent en SQLite. Base64 estándar de 32 bytes (44 caracteres). Generar con `openssl rand -base64 32`; conservar y no cambiar si hay credenciales guardadas. | Vacío |
| `EPLSYNC_INITIAL_ADMIN_KEY` | Clave opcional para autorizar la creación del primer administrador. Generar con `openssl rand -hex 16`. Vacía no exige clave; después de crear la primera cuenta deja de tener efecto. | Vacío |
| `EPLSYNC_SECURITY_REQUIRE_HTTPS` | true rechaza HTTP y marca la cookie como Secure; false permite HTTP y HTTPS. No configura certificados ni el proxy. | `false` |
| `EPLSYNC_SECURITY_PASSWORD_MIN_LENGTH` | Longitud mínima inicial de contraseña (8–128). Después se administra en Usuarios y seguridad. | `8` |
| `EPLSYNC_CATALOG_ZIPURL` | URL del ZIP utilizado para importar el catálogo. | `https://www.dropbox.com/s/a9r4p7oyaftaz1b/csv_full_imgs.zip?dl=1` |
| `EPLSYNC_CATALOG_IMPORT_RETENTION` | Tiempo de conservación del ZIP de importación guardado. | `24h` |
| `EPLSYNC_CATALOG_IMPORT_OPERATION_TIMEOUT` | Presupuesto compartido de importación, previsualización y comprobación de ausentes, incluyendo carga, descarga y escritura. Duración positiva, máximo `24h`. La cancelación es cooperativa; una llamada bloqueada puede tardar en devolver el control. | `30m` |
| `EPLSYNC_CATALOG_COVERCHECK_CONNECTTIMEOUT` | Tiempo máximo para establecer una conexión al comprobar portadas. | `3s` |
| `EPLSYNC_CATALOG_COVERCHECK_REQUESTTIMEOUT` | Presupuesto por URL, incluidas redirecciones; debe ser mayor o igual al tiempo de conexión. | `3s` |
| `EPLSYNC_CATALOG_COVERCHECK_BATCHTIMEOUT` | Presupuesto del grupo de comprobaciones; debe ser mayor o igual al tiempo por URL. | `4s` |
| `EPLSYNC_CATALOG_COVERCHECK_CONCURRENCY` | Número de URL de portadas comprobadas simultáneamente (1–32). | `8` |
| `EPLSYNC_TORRENT_ENABLED` | Activa la integración torrent; configurar conexión y autenticación antes de activarla. | `false` |
| `EPLSYNC_TORRENT_CLIENT` | Adaptador del cliente torrent; actualmente qbittorrent. | `qbittorrent` |
| `EPLSYNC_TORRENT_BASEURL` | URL HTTP(S) de la WebUI de qBittorrent, accesible desde el contenedor. | `http://qbittorrent:8080` |
| `EPLSYNC_TORRENT_CONNECTTIMEOUT` | Tiempo máximo para establecer una conexión con el cliente torrent. | `5s` |
| `EPLSYNC_TORRENT_REQUESTTIMEOUT` | Tiempo máximo por petición HTTP al cliente torrent, incluida la recepción del cuerpo. | `60s` |
| `EPLSYNC_TORRENT_MAX_RESPONSE_SIZE` | Tamaño máximo real de los listados de qBittorrent (también sin Content-Length). Entre 1 KiB y 1 GiB. Las respuestas auxiliares tienen un máximo fijo de 1 MiB. | `256MB` |
| `EPLSYNC_TORRENT_MAX_REMOTE_TORRENTS` | Máximo de torrents por listado; entre 1 y 1000000. Superarlo aborta la operación antes de reconciliar registros. | `500000` |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE` | Modo de autenticación: auto, api-key o session. auto prueba la API key si existe y puede recurrir a usuario y contraseña si están configurados. | `auto` |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME` | Necesaria con la integración activa y auth.mode=session, o con auto sin API key. Debe acompañarse de contraseña. | Vacío |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_PASSWORD` | Necesaria junto al usuario en autenticación por sesión. No es la contraseña de un usuario de EPL Sync. | Vacío |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY` | Necesaria con la integración activa y auth.mode=api-key, o con auto si eliges API key. | Vacío |
| `EPLSYNC_TORRENT_TRACKERS` | Lista de trackers adicionales separada por comas. Vacío elimina la lista. | `udp://tracker.opentrackr.org:1337/announce,udp://www.torrent.eu.org:451/announce,udp://exodus.desync.com:6969/announce,udp://p4p.arenabg.ch:1337/announce,udp://tracker.internetwarriors.net:1337/announce` |
| `EPLSYNC_TORRENT_BULK_MULTIPLEHASHES` | Tratamiento de libros con varios hashes: all, first o skip. | `all` |
| `EPLSYNC_TORRENT_BULK_BATCHSIZE` | Número de libros procesados por lote en los trabajos de descargas (1–1000). | `100` |
| `EPLSYNC_TORRENT_BULK_CONCURRENCY` | Número máximo de envíos simultáneos en un trabajo de descargas (1–16). | `1` |
| `EPLSYNC_TORRENT_BULK_INTERVAL` | Intervalo entre tandas de envío, entre 0 y 60 segundos; 0 elimina la pausa. | `500ms` |
| `EPLSYNC_TORRENT_BULK_RETENTION_ENABLED` | Elimina trabajos finalizados caducados y sus elementos. Conserva trabajos con envíos o limpiezas pendientes; no borra historial de descargas, eventos, torrents ni archivos. | `true` |
| `EPLSYNC_TORRENT_BULK_RETENTION_DAYS` | Días de conservación desde la última actualización del trabajo finalizado, entre 1 y 36500. | `90` |
| `EPLSYNC_TORRENT_BULK_RETENTION_INTERVAL` | Espera positiva entre ciclos de retención. Cada ciclo elimina como máximo 20 trabajos, en transacciones independientes. | `1h` |
| `EPLSYNC_TORRENT_CLEANUP_ENABLED` | Activa las comprobaciones periódicas de limpieza. Desactivarla conserva los pendientes y permite acciones manuales. | `true` |
| `EPLSYNC_TORRENT_CLEANUP_INTERVAL` | Espera entre ciclos de limpieza, positiva; por ejemplo `30s` o `1m`. | `60s` |
| `EPLSYNC_TORRENT_CLEANUP_BATCH_SIZE` | Solicitudes de limpieza por ciclo, entre 1 y 1000. El límite se aplica a registros, no a trabajos. | `100` |
| `EPLSYNC_TORRENT_DOWNLOAD_START` | true inicia las descargas enviadas; false las deja detenidas. | `true` |
| `EPLSYNC_TORRENT_DOWNLOAD_SAVEPATH` | Ruta de destino en qBittorrent, no en EPL Sync. Vacío utiliza la ruta del cliente. | Vacío |
| `EPLSYNC_TORRENT_RENAME_ENABLED` | Activa el renombrado del nombre mostrado del torrent al enviarlo. | `true` |
| `EPLSYNC_TORRENT_RENAME_PATTERN` | Patrón de renombrado; admite campos del libro como {author}, {title}, {eplId} y {revision}. | `'{author} - {title} [{eplId}] (r{revision})'` |
| `EPLSYNC_TORRENT_QBITTORRENT_DOWNLOAD_CATEGORY` | Categoría de los torrents enviados. Vacío omite la categoría. | `Libros` |
| `EPLSYNC_TORRENT_QBITTORRENT_DOWNLOAD_TAGS` | Etiquetas separadas por comas; admite marcadores como {language}. Vacío elimina la lista. | `'EPLsync,{language}'` |
| `EPLSYNC_TORRENT_QBITTORRENT_DOWNLOAD_AUTOMANAGEMENT` | Activa la gestión automática de torrents de qBittorrent. | `true` |
| `EPLSYNC_EVENTS_RETENTION_MAX_COUNT` | Número máximo de eventos conservados; se aplica junto con el límite por antigüedad. | `10000` |
| `EPLSYNC_EVENTS_RETENTION_MAX_AGE_DAYS` | Antigüedad máxima de los eventos, en días; se aplica junto con el límite por cantidad. | `365` |

## Calibre

| Variable | Descripción | Valor predeterminado |
| --- | --- | --- |
| `EPLSYNC_CALIBRE_URL` | URL del servidor de contenido de Calibre, HTTP o HTTPS. | Vacío |
| `EPLSYNC_CALIBRE_BROWSER_URL` | URL opcional accesible desde el navegador para abrir libros. Vacía utiliza la URL del servidor de contenido. | Vacío |
| `EPLSYNC_CALIBRE_USERNAME` | Usuario del servidor de contenido. | Vacío |
| `EPLSYNC_CALIBRE_PASSWORD` | Contraseña del servidor de contenido. No se copia a la base de datos. | Vacío |
| `EPLSYNC_CALIBRE_LIBRARY` | Identificador de biblioteca, disponible al comprobar la conexión en Ajustes > Calibre. | Vacío |

Los ajustes guardados desde la interfaz tienen prioridad sobre estos valores de
instalación. Las variables se aplican inicialmente cuando todavía no hay una
configuración guardada. Guardar sin introducir una contraseña nueva conserva la
contraseña de entorno para el mismo servidor y usuario; cambiar cualquiera de
ellos impide reutilizarla. Eliminar la contraseña desde Ajustes impide su
reutilización también después de reiniciar. Guardar una contraseña nueva desde
la interfaz requiere `EPLSYNC_SECRET_KEY` y la almacena cifrada.

Cambiar las variables requiere recrear el contenedor. Configurarlas no ejecuta
una sincronización: se realiza desde Calibre > Biblioteca.

## Valores sin la plantilla

El Compose utiliza los mismos valores de reserva que `.env.example`.
Los valores definidos en `.env` los sustituyen. Para las variables que permiten
listas o rutas vacías, como trackers, etiquetas o ruta de descarga, un valor vacío
conserva ese significado.

## Trackers predeterminados

`EPLSYNC_TORRENT_TRACKERS` sustituye toda la lista. El valor predeterminado contiene:

- `udp://tracker.opentrackr.org:1337/announce`
- `udp://www.torrent.eu.org:451/announce`
- `udp://exodus.desync.com:6969/announce`
- `udp://p4p.arenabg.ch:1337/announce`
- `udp://tracker.internetwarriors.net:1337/announce`

## Aplicación de los valores

- Las personalizaciones del catálogo, portadas, torrent y eventos guardadas desde Ajustes tienen prioridad sobre los valores de instalación. Restaurar valores de instalación vuelve a utilizar los de la configuración.
- La longitud mínima de contraseña es un valor inicial persistido; después se modifica desde Usuarios y seguridad.
- Para cambiar variables de entorno, recrea el contenedor con `docker compose up -d`. Para cambiar `PUID` o `PGID`, adapta los permisos de las carpetas del host y recrea el contenedor; no necesitas reconstruir la imagen.
- Duraciones como `3s`, `500ms` y `24h` incluyen su unidad. Las listas de trackers y tags se separan con comas.
- En `.env`, encierra entre comillas simples los valores que contengan un `$` literal, especialmente contraseñas. No guardes `.env` en Git.
- Compose traduce `EPLSYNC_EVENTS_RETENTION_MAX_COUNT` y `EPLSYNC_EVENTS_RETENTION_MAX_AGE_DAYS` a `EPLSYNC_EVENTS_RETENTION_MAXCOUNT` y `EPLSYNC_EVENTS_RETENTION_MAXAGEDAYS` dentro del contenedor. La tabla usa los nombres de `.env`.
- Las opciones avanzadas del proxy (`server.forward-headers-strategy` y `server.tomcat.remoteip.internal-proxies`) permanecen documentadas en `application.yaml` y fuera del Compose. Consulta [Usuarios y seguridad](seguridad.md) para configurarlas mediante un YAML externo o variables adicionales.

### ARCHIVOS EPUB DESCARGADOS

| Variable | Valor predeterminado | Uso |
| --- | --- | --- |
| `EPLSYNC_TORRENT_FILES_REMOTE_ROOT` | Vacío | Carpeta base absoluta vista por qBittorrent, por ejemplo `/downloads`. |
| `EPLSYNC_TORRENT_FILES_LOCAL_ROOT` | `/app/downloads` en Docker; vacío en el JAR | La misma carpeta vista por EPL Sync, por ejemplo `/app/downloads`. |

Se pueden modificar en Ajustes > Torrent. La variable de ruta local se admite
como configuración opcional, pero no se incluye en Compose ni en `.env.example`:
la imagen Docker ya define `/app/downloads`. La carpeta base de qBittorrent vacía
desactiva el acceso. Para activarlo,
ambas rutas deben estar configuradas.
Docker necesita además un montaje de esa carpeta de solo lectura; las variables
no crean el montaje. Consulta [Descargas](descargas.md#descargar-los-epub-al-navegador).
