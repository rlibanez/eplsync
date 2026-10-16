# Logs y diagnóstico

[Guías](README.md) · [Inicio](../README.md)

## Eventos y notificaciones

En **Ajustes → Notificaciones** puedes activar o desactivar los avisos
flotantes y el contador de eventos sin leer de forma independiente. Los filtros
de inicio, final y resultado, y la duración de los avisos (de 1 a 60 segundos),
se guardan en tu cuenta y se aplican también en otros navegadores.

Desactivar los avisos no impide registrar eventos ni oculta los errores dentro de las vistas.
La política depende de la operación, tanto desde su vista como desde una tarea con
**Ejecutar ahora** o por horario:

- Las actualizaciones del catálogo conservan sus eventos de inicio y resultado, sin un evento adicional de «Tarea programada».
- Las sincronizaciones de Torrent y Calibre, las búsquedas de actualizaciones y los trabajos de envío guardan solo fallos o resultados parciales. El detalle de cada elemento permanece en su trabajo.
- Los avisos de inicio y resultado correcto se transmiten en directo sin añadir eventos al historial ni incrementar el contador. No se reproducen al recargar.
- Un mismo identificador de operación y resultado produce un único aviso, aunque llegue por el canal en directo y por el historial. Se respetan los ajustes de inicio, final y resultado de cada cuenta.

El resto de operaciones mantiene su registro habitual. Telegram conserva su configuración independiente y solo avisa de tareas con las notificaciones habilitadas.

## Resúmenes de operaciones y mantenimiento

El nivel del resumen final depende del resultado y de la operación, no de si se
inicia desde una vista, con **Ejecutar ahora** o por horario:

| Operación | Resumen final correcto |
| --- | --- |
| Importación del catálogo | INFO, después de confirmar la transacción |
| Sincronización de Torrent y Calibre | INFO |
| Búsqueda de actualizaciones del catálogo y de Calibre | INFO, después de guardar los resultados |
| Envíos a Torrent y Calibre | INFO por trabajo; los elementos conservan su detalle propio |
| Comprobación de portadas y de versiones de EPL Sync | INFO |
| Vinculación de un torrent y eliminación del ZIP de catálogo caducado | INFO una vez al aplicar el cambio |
| Retención de trabajos | INFO si elimina trabajos; WARN con los contadores si queda incompleta |

El progreso de las importaciones programadas y el inicio de su sincronización
Torrent pueden usar DEBUG. El resumen genérico de una tarea correcta también se
mantiene en DEBUG para acompañar el resumen INFO de su operación sin duplicarlo
en el nivel habitual. Los fallos de tareas conservan su diagnóstico WARN.

Los ciclos sin actividad de limpieza, retención, temporales y colas no generan
un resumen INFO en cada comprobación. Los errores de mantenimiento conservan
WARN o ERROR según su gravedad. Una entrega fallida de Telegram emite un solo
WARN final; el diagnóstico técnico se escribe en DEBUG y el siguiente ciclo no
repite la entrega terminal.

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
