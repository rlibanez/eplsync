# Logs y diagnóstico

[Guías](README.md) · [Inicio](../README.md)

## Consultar los logs

Desde la carpeta de la instalación:

```sh
docker compose logs -f eplsync
```

También se conservan en la carpeta `LOGS_DIR`, por defecto `./logs`:

- `eplsync.log`: actividad de la aplicación, errores e inicios y cierres de sesión.
- `access.log`: peticiones recibidas, código de respuesta y duración.

Si un error muestra un identificador de incidencia, búscalo en `eplsync.log`
para encontrar los detalles técnicos.

## Conservación

El log principal rota al cambiar de día y al alcanzar aproximadamente 50 MB.
Sus históricos se conservan hasta 30 días, con un límite adicional de 1 GB.
El log de acceso rota diariamente y conserva 30 días; utiliza una retención
independiente y no tiene ese límite de tamaño.

## Errores habituales

- **Permiso denegado al iniciar:** revisa el propietario y permisos de las carpetas
  `DATA_DIR` y `LOGS_DIR`, y los valores `PUID` y `PGID`.
- **No se puede conectar con qBittorrent:** comprueba la URL, red Docker, puerto
  de la WebUI y autenticación en **Ajustes → Torrent**.
- **Se requiere una conexión segura:** revisa HTTPS y las cabeceras del proxy
  según la [guía de acceso remoto](seguridad.md#acceso-remoto).
- **Credenciales guardadas inaccesibles:** restablece el valor original de
  `EPLSYNC_SECRET_KEY` y recrea el contenedor.

La IP registrada corresponde al cliente que conecta con la aplicación. Con Docker
o un proxy puede ser la dirección de la pasarela o del proxy, según la configuración.
Los logs de acceso contienen las URL y sus parámetros; evita compartir información
sensible cuando solicites ayuda.
