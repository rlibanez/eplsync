# Trabajos de envío

[Guías](README.md) · [Inicio](../README.md)

Los trabajos controlan los envíos de libros y actualizaciones a qBittorrent.
Un envío aceptado no implica una descarga terminada.

## Consultar trabajos

En **Descargas → Trabajos** puedes filtrar por tipo, estado, usuario y fechas.
La tabla muestra quién inició cada trabajo. Utiliza **Ordenar** y **Columnas**
para adaptar su presentación.

Abre un trabajo para consultar su informe, opciones de envío, progreso, resultados
por libro y política de versiones anteriores. La tabla de elementos permite
filtrar por estado, ordenar, ajustar columnas y consultar los errores.

## Pausar, reanudar y cancelar

Los controles disponibles dependen del estado del trabajo y de tus permisos.

- **Pausar:** detiene los siguientes envíos; las solicitudes en curso pueden terminar.
- **Reanudar:** continúa los elementos pendientes.
- **Cancelar:** cancela los envíos pendientes.

Pausar o cancelar un trabajo no detiene ni elimina los torrents ya enviados.
Si se desactiva la integración o cambia el destino o la autenticación del cliente,
los trabajos activos pueden pausarse antes del siguiente envío; revisa su informe
antes de reanudarlos.

## Conservación

Los trabajos completados o cancelados se conservan por defecto durante 90 días
desde su última actualización. La retención conserva los que tienen operaciones
o limpiezas pendientes. Puede configurarse mediante las
[variables de instalación](variables-entorno.md).

Eliminar trabajos por retención no borra el historial de descargas, los eventos,
los torrents ni sus archivos.
