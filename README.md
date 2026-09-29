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

Edita `.env`: configura la URL y la API key de qBittorrent, o establece
`EPLSYNC_TORRENT_ENABLED=false` para utilizar solo el catálogo. Ajusta `PUID` y
`PGID` al usuario que tiene permisos de escritura en `data/` y `logs/`
(en Linux, consulta `id -u` e `id -g`).

```sh
docker compose up -d --build
curl http://localhost:8088/actuator/health
```

La imagen se compila sin instalar Java ni Maven en el host. El puerto por defecto
es `8088` (configurable con `HOST_PORT`); los datos y logs persisten en `data/`
y `logs/`. La API no incluye autenticación: utiliza una red de confianza o un
proxy con control de acceso.

## Primer uso

Importa o actualiza el catálogo:

```sh
curl -X POST http://localhost:8088/api/catalog/import/update
```

## Documentación

- [Instalación y configuración detallada](docs/instalacion.md).
- [Referencia de la API](docs/API.md).
- [Novedades, revisiones nuevas y limpieza](docs/actualizaciones.md): envío combinado
  mediante `GET/POST /api/torrent/refresh?language=es`.
- [Todas las guías](docs/README.md): catálogo, qBittorrent, jobs, descargas,
  actualizaciones, logs y desarrollo.
