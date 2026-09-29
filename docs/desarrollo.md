# Desarrollo y verificación

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

Para compilar y ejecutar las pruebas localmente necesitas Java 25. El repositorio incluye el wrapper de Maven.

## Verificación del proyecto

Las pruebas usan SQLite en memoria y no modifican la base de datos local.
Para compilar, ejecutar todas las pruebas y generar el JAR:

```sh
./backend/mvnw -f backend/pom.xml clean verify
```

## Arquitectura de adaptadores

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

