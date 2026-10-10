# Instalación y despliegue

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Ejecutar con Docker

Desde la raíz del repositorio, con Docker y Docker Compose v2 o posterior,
prepara la configuración local (solo la primera vez):

```sh
cp .env.example .env
chmod 600 .env
mkdir -p data logs
```

`docker-compose.yml` es la configuración mínima: permite configurar la aplicación
desde Ajustes. Para transmitir todos los valores iniciales de `.env`, utiliza
`docker-compose-full.yml`. Con este último, para utilizar qBittorrent ajusta su URL,
configura una API key o usuario y contraseña, y establece `EPLSYNC_TORRENT_ENABLED=true`.
Para guardar credenciales desde Ajustes, genera la clave con `openssl rand -base64 32`
y copia el resultado en `EPLSYNC_SECRET_KEY` dentro de `.env`. Conserva ese valor
y no lo cambies después de guardar credenciales. Ambos Compose lo transmiten.
La plantilla deja la integración desactivada y las credenciales vacías. `.env.example` contiene valores de ejemplo;
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
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.yml logs -f eplsync
curl http://localhost:8088/actuator/health
```

Para utilizar el archivo completo:

```sh
docker compose -f docker-compose-full.yml pull
docker compose -f docker-compose-full.yml up -d
docker compose -f docker-compose-full.yml logs -f eplsync
```

Para construir el código local, copia `docker-compose.override.example.yml` a
`docker-compose.override.yml` si aún no tienes un override. La plantilla incluye
toda la configuración del completo y construye la imagen local.
Usa `REVISION=$(git rev-parse HEAD) docker compose up -d --build`: se carga
automáticamente `docker-compose.override.yml` y se genera `eplsync:local`.
Los comandos con `-f` anteriores utilizan exclusivamente la imagen de GHCR.
Para el completo local, añade `-f docker-compose.override.yml` tras el archivo completo.

Los dos archivos son alternativas completas para la misma instalación; elige uno
y úsalo en todos los comandos (`up`, `logs`, `down`, etc.). No hace falta combinarlos.
Comparten servicio, imagen, puerto y montajes. `.env.example` se conserva como
plantilla única. Consulta [todas las variables y sus valores predeterminados](variables-entorno.md).

La imagen compila el backend con el wrapper Maven y Java 25 en una etapa separada;
la ejecución usa un JRE 25 y un usuario sin privilegios, con el UID/GID indicado.
No requiere Java ni Maven instalados en el host. `PUID` y `PGID` se aplican al
ejecutar el contenedor mediante `user`: si los cambias, ajusta los permisos y
recrea con `docker compose -f docker-compose.yml up -d`, sin reconstruir la imagen. El build omite los tests; para validarlos antes
puedes ejecutar `cd backend && ./mvnw verify`.

El puerto publicado por defecto es `8088`, limitado a `127.0.0.1`.
Puedes cambiarlo con `HOST_PORT` y la interfaz con `HOST_BIND` en `.env`.
Crea el primer administrador desde el asistente web, con username, email y contraseña confirmada.
La API requiere sesión, permisos y CSRF para escrituras. Para acceso remoto configura HTTPS;
consulta [Seguridad](seguridad.md).

SQLite y los jobs/historial se guardan en `./data/eplsync.db` del host, mediante
el montaje de `./data` en `/app/data`. Si la BD ya existe, el contenedor la utiliza;
si no existe, la aplicación la crea. Tanto `docker compose down` como
`docker compose down -v` conservan estas carpetas del host y sus datos.
No ejecutes dos instancias contra la misma base SQLite.

## Configuración y conexión con qBittorrent

`.env` está excluido de Git y del contexto de build. `.env.example` se versiona
como plantilla y también queda fuera del contexto de build.
Compose utiliza `.env` para interpolar el YAML. Con `docker-compose-full.yml`, todas las variables de la plantilla
se utilizan: `PUID` y `PGID` en el usuario de ejecución; `HOST_BIND` y `HOST_PORT` en el
puerto publicado; `DATA_DIR` y `LOGS_DIR` en los montajes; el resto se transmite
al contenedor mediante `environment`.

El mínimo transmite `TZ` y `EPLSYNC_SECRET_KEY`, además de usar las variables del usuario de ejecución,
puertos y montajes. Las demás variables de `.env` no se transmiten con el mínimo.
Para aplicarlas, utiliza el Compose completo. Las opciones disponibles en Ajustes
también se pueden modificar desde la interfaz.

La plantilla está agrupada por contenedor, almacenamiento, seguridad,
catálogo, portadas, conexión y autenticación torrent, trackers, trabajos, opciones
de envío y retención de eventos. `TZ` permite cambiar la zona horaria.
`DATA_DIR` y `LOGS_DIR` conservan `./data` y `./logs` por defecto; si las cambias,
adapta también los comandos de creación de carpetas y sus permisos.

Con el Compose completo, `EPLSYNC_SECURITY_REQUIRE_HTTPS=true` exige HTTPS y marca la cookie de sesión como
Secure; `false` admite HTTP y HTTPS. No configura certificados ni el proxy.
Las opciones avanzadas del proxy permanecen documentadas en `application.yaml`,
fuera de la plantilla habitual; consulta [Seguridad](seguridad.md).

Los valores del catálogo, portadas, torrent y eventos son valores de instalación:
los ajustes guardados desde la interfaz tienen prioridad. La longitud mínima de
contraseña se usa al inicializar la configuración de seguridad; después se
administra desde Usuarios y seguridad.

Los nombres de variables siguen el binding nativo de Spring: puntos por guiones
bajos y sin guiones dentro de los nombres (`base-url` → `BASEURL`,
`api-key` → `APIKEY`). Para eventos, Compose traduce los nombres legibles
`MAX_COUNT` y `MAX_AGE_DAYS` de `.env` a `MAXCOUNT` y `MAXAGEDAYS` del contenedor.
No se utiliza `env_file` con `format: raw`; si un valor de `.env` contiene un `$`
literal, enciérralo entre comillas simples para evitar su interpolación por Compose.
Las listas de trackers y tags usan comas; un valor vacío elimina la lista.

Tras modificar las variables transmitidas, ejecuta `docker compose up -d` para
recrear el contenedor; añade `-f docker-compose-full.yml` si elegiste el completo. No cambies `SERVER_PORT` si mantienes el mapeo de puertos
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


## Idioma de la interfaz

El idioma inicial se obtiene de las preferencias del navegador. Se selecciona
el primer idioma disponible en EPL Sync; si no hay coincidencias, se utiliza
inglés. Las variantes regionales, como `es-ES`, se reconocen. Una selección
manual guardada en el navegador tiene prioridad sobre esa detección.
No hace falta configurar variables, recrear el contenedor ni consultar el servidor.

Desde «Mantenimiento → Actualizar catálogo» puedes previsualizar e importar el
CSV configurado, incluso para la primera carga. La interfaz y la API comparten
el mismo puerto publicado.

## Ajustes del servidor desde la interfaz

Los apartados Base de datos, Portadas, Torrent y General (conservación de eventos)
permiten guardar valores predeterminados del servidor. Se persisten en la tabla
`app_settings` de `data/eplsync.db`: sobreviven a reinicios y reconstrucciones del
contenedor. La aplicación no escribe en `application.yaml`, Compose ni `.env`.

La prioridad, de mayor a menor, es:

1. Opciones explícitas de una operación, cuando el endpoint las admite.
2. Ajustes guardados en la base de datos.
3. Valores de instalación resueltos por Spring desde variables de entorno/YAML.

Los cambios se aplican también a peticiones REST externas. Cada petición usa una
instantánea de los ajustes; las comprobaciones de portadas y los trabajos en
marcha conservan sus opciones. Los trabajos guardan sus parámetros de envío al
crearse y toman la conexión vigente cuando comienzan a ejecutarse. La conexión
qBittorrent se renueva para nuevas operaciones sin reiniciar el contenedor.

**Guardar** guarda los campos editados del apartado. **Restaurar valores de
instalación** elimina sus personalizaciones y recupera los valores resueltos al
arrancar. Para cambiar estos últimos en YAML/Docker es necesario reiniciar; los
ajustes guardados siguen teniendo prioridad hasta que se restauren.

Contraseña y API key nunca se devuelven en las respuestas de ajustes. Un campo
vacío sin editar conserva la credencial; el botón «Borrar credencial» solicita
borrarla al guardar. Las credenciales guardadas forman parte de la base de datos
y sus copias de seguridad; no se cifran por esta funcionalidad.

El reinicio conserva las cuentas y `app_settings` por defecto. La opción de borrar
usuarios y toda la configuración elimina también esos datos y recupera los valores de instalación. La retención de eventos se aplica en la limpieza automática
por lotes cada minuto; la conservación de un ZIP se fija cuando se descarga o carga.

## Versión instalada y actualizaciones

Ajustes > Acerca de muestra la versión instalada y el identificador corto del commit,
cuando está disponible. La imagen incorpora los argumentos de construcción `VERSION`
y `REVISION` en los metadatos del backend, además de las etiquetas OCI. En una
construcción local, el comando con `REVISION=$(git rev-parse HEAD)` indicado arriba
incorpora el commit; si se omite, se muestra únicamente la versión.

Los administradores pueden comprobar nuevas versiones estables publicadas en
GitHub y abrir sus notas. Se aceptan etiquetas `v0.0.1` o `0.0.1`; no se anuncian
versiones preliminares. La comprobación automática se realiza al iniciar y cada
24 horas, y puede desactivarse en Acerca de. Su valor inicial procede de
`EPLSYNC_UPDATE_CHECK_ENABLED` (por defecto `true`); los cambios de la interfaz
se guardan en la base de datos. La comprobación manual reutiliza el resultado
durante un minuto para evitar consultas repetidas.

La consulta envía a GitHub únicamente una solicitud de la última versión pública.
Un fallo de conexión no impide usar EPL Sync. Los avisos se muestran a administradores
y no instalan actualizaciones: la imagen se actualiza mediante el procedimiento
habitual de la instalación.
