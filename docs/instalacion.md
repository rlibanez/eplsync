# Instalación y configuración

[Guías](README.md) · [Inicio](../README.md)

## Archivos de instalación

Sigue los pasos del [README](../README.md#instalación-con-docker). Descarga `docker-compose.yml`
y la plantilla `.env.example`, guardando esta última como `.env`, en una carpeta
dedicada a la instalación. No necesitas descargar el código fuente.

Para empezar, revisa la versión, usuario y grupo, puerto, carpetas, zona horaria y
claves de seguridad. Las demás variables pueden conservar sus valores
predeterminados. La [tabla de variables](variables-entorno.md) explica todas las
opciones; los ajustes disponibles en la interfaz también pueden configurarse
desde **Ajustes**.

`.env` y `docker-compose.yml` deben estar en la misma carpeta. Los comandos
siguientes se ejecutan desde ella.

## Carpetas y permisos

Por defecto se conservan datos en `./data` y logs en `./logs`. Crea ambas carpetas
antes del primer inicio:

```sh
mkdir -p data logs
id -u
id -g
```

Utiliza los identificadores de tu usuario en `PUID` y `PGID` si las carpetas le
pertenecen. Para asignar carpetas existentes a otros identificadores, por ejemplo
`1000:1000`:

```sh
sudo chown -R 1000:1000 data logs
```

Adapta los números y las rutas a tu configuración. Cambiar `PUID` o `PGID` no
modifica automáticamente el propietario de los archivos existentes.

Protege `.env`, que puede contener credenciales:

```sh
chmod 600 .env
```

Detener o eliminar el contenedor no borra estas carpetas del host. No ejecutes
dos instancias de EPL Sync utilizando la misma carpeta de datos.

## Clave para guardar credenciales

Para configurar contraseñas o API keys de qBittorrent desde Ajustes, genera una
clave de instalación:

```sh
openssl rand -base64 32
```

Copia el resultado como valor de `EPLSYNC_SECRET_KEY` en `.env`. Conserva esa
clave: cambiarla o perderla impide recuperar las credenciales guardadas.
La aplicación también ofrece generar y copiar una clave si falta al guardar.

## Iniciar, detener y consultar logs

```sh
docker compose pull
docker compose up -d
docker compose logs -f eplsync
```

Para detener la instalación:

```sh
docker compose down
```

## Aplicar cambios de configuración

Después de editar `.env` o `docker-compose.yml`:

```sh
docker compose up -d
```

Los valores guardados desde Ajustes tienen prioridad sobre sus valores iniciales
cuando existe una personalización. **Restaurar valores de instalación** recupera
los valores de la configuración de despliegue para ese apartado.

Los valores con un `$` literal deben ir entre comillas simples en `.env`.
Las listas de trackers y etiquetas se separan con comas; las duraciones incluyen
unidad, como `5s`, `500ms` o `24h`.

## Acceso y primer inicio

Por defecto, abre `http://localhost:8088` desde el equipo que ejecuta Docker.
`HOST_PORT` cambia el puerto publicado, accesible en todas las interfaces del host. Para acceso remoto, consulta [Usuarios y seguridad](seguridad.md#acceso-remoto).

Crea el primer administrador desde el asistente web. Importa después el catálogo
en **Ajustes → Catálogo → Importar catálogo** y configura el cliente en
**Ajustes → Torrent**.

El idioma inicial sigue las preferencias del navegador, con inglés como alternativa.
Puedes elegir otro en **Ajustes → General**.

## Actualizar EPL Sync

En **Ajustes → Acerca de** puedes consultar la versión instalada, las notas de esa
versión y comprobar nuevas versiones estables. La comprobación automática puede
desactivarse desde esa vista. Los avisos no instalan actualizaciones.

Para actualizar, cambia `EPLSYNC_VERSION` en `.env` y ejecuta:

```sh
docker compose pull
docker compose up -d
```

Conserva las carpetas de datos, logs y la clave de cifrado.
