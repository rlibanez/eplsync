# EPL Sync

Servicio para importar y consultar el catálogo de ePubLibre, generar enlaces
magnet y gestionar envíos y actualizaciones con qBittorrent. Guarda el catálogo
y el historial en SQLite.

## Instalación con Docker

Necesitas Docker y Docker Compose v2 o posterior. Desde la raíz del repositorio:

```sh
cp .env.example .env
mkdir -p data logs
```

`docker-compose.yml` es la configuración mínima: utiliza los valores de la
aplicación y permite configurar el resto desde **Ajustes**. Ajusta `PUID` y `PGID`
al usuario que tiene permisos de escritura en `data/` y `logs/`.

```sh
docker compose up -d --build
curl http://localhost:8088/actuator/health
```

Para personalizar todos los valores iniciales mediante `.env`, utiliza
`docker-compose-full.yml`:

```sh
docker compose -f docker-compose-full.yml up -d --build
```

Son alternativas para la misma instalación. Usa el archivo elegido también para
`logs`, `down` y posteriores actualizaciones. `.env.example` sirve para ambos;
las variables exclusivas del completo no se aplican con el mínimo. Para activar
qBittorrent desde `.env`, configura su URL y autenticación y establece
`EPLSYNC_TORRENT_ENABLED=true` usando el Compose completo; con el mínimo, hazlo
desde Ajustes.

Consulta la [tabla de variables de instalación](docs/variables-entorno.md)
para consultar sus descripciones y valores predeterminados.

Abre `http://localhost:8088` para acceder a la interfaz web (Inicio, catálogo y
fichas de libros, mantenimiento y ajustes).

La imagen compila React/TypeScript y Java sin instalar Node, Java ni Maven en el host. El puerto por defecto
es `8088` (configurable con `HOST_PORT`); los datos y logs persisten en `data/`
y `logs/`. Incluye usuarios, permisos y sesiones. El puerto se publica únicamente
en localhost por defecto; consulta [Seguridad](docs/seguridad.md) para acceso remoto.

## Primer uso

Abre la aplicación web y crea el primer administrador indicando username, email,
contraseña y su confirmación. El asistente se cierra en cuanto existe la primera cuenta.
Desde la interfaz, abre **Mantenimiento → Actualizar catálogo** para previsualizar
e importar los libros. El idioma inicial se obtiene de las preferencias del navegador;
si ninguna tiene traducción disponible, se utiliza inglés. Una elección manual
guardada en el navegador tiene prioridad.

Las llamadas de escritura a la API requieren sesión y token CSRF; consulta
[Usuarios y seguridad](docs/seguridad.md).

## Documentación

- [Instalación y configuración detallada](docs/instalacion.md).
- [Variables de Docker Compose](docs/variables-entorno.md).
- [Referencia de la API](docs/API.md).
- [Novedades, revisiones nuevas y limpieza](docs/actualizaciones.md): envío combinado
  mediante `GET/POST /api/torrent/refresh?language=es`.
- [Todas las guías](docs/README.md): catálogo, qBittorrent, jobs, descargas,
  actualizaciones, logs y desarrollo.
