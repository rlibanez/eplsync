# Configurar qBittorrent

[Guías](README.md) · [Inicio](../README.md)

## Conexión

En **Ajustes → Torrent**, configura:

1. La URL de la WebUI de qBittorrent, accesible desde el contenedor de EPL Sync.
2. El modo de autenticación y su API key o usuario y contraseña.
3. **Habilitar integración torrent**, cuando vayas a utilizarla.

**Comprobar conexión** utiliza los valores actuales del formulario y puede
ejecutarse con la integración desactivada. El resultado informa del cliente,
autenticación y versiones, o explica el error. Guarda los ajustes para aplicar
los cambios a las siguientes operaciones.

La conexión y sus credenciales solo pueden modificarlas administradores.
Para guardar credenciales desde Ajustes necesitas la
[clave de cifrado de la instalación](seguridad.md#credenciales-de-qbittorrent).
Al cambiar la URL, introduce las credenciales del nuevo servidor.

## Elegir la URL

`localhost` dentro de EPL Sync corresponde a su propio contenedor. Para acceder
a qBittorrent utiliza una dirección alcanzable desde él:

- **Otro contenedor:** nombre de servicio y puerto interno, como
  `http://qbittorrent:8080`, si su WebUI escucha en ese puerto. Ambos deben
  compartir una red Docker.
- **Otro equipo:** su IP o dominio y el puerto de la WebUI.
- **El host Docker:** su dirección accesible; también puedes utilizar
  `host.docker.internal` si está configurado. En Linux puede requerir añadir
  `extra_hosts: ["host.docker.internal:host-gateway"]` al servicio.

EPL Sync no necesita montar las carpetas de descarga del cliente.

## Opciones de envío

**Ajustes → Torrent** permite configurar valores predeterminados para:

- Iniciar la descarga o añadirla detenida.
- Ruta de destino, categoría, etiquetas y gestión automática del cliente.
- Renombrado y patrón del nombre mostrado del torrent.
- Tratamiento de libros con varios hashes y ritmo de envío de trabajos.

La ruta pertenece al equipo o contenedor de qBittorrent. Vacía utiliza la ruta
del cliente. Crea previamente en qBittorrent las categorías que vayas a utilizar.

El patrón predeterminado de renombrado es:

```text
{author} - {title} [{eplId}] (r{revision})
```

Las opciones pueden revisarse al enviar libros desde Catálogo o Actualizaciones.
Un envío aceptado no significa que la descarga haya finalizado: comprueba su
[estado](descargas.md) y el [trabajo de envío](jobs.md).
