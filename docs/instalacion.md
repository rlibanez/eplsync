# Instalación y despliegue

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

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

## Configuración y conexión con qBittorrent

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

