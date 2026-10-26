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

## Migraciones de base de datos

Flyway ejecuta todas las migraciones versionadas, en orden, al inicializar una base de datos vacía. La primera crea el esquema inicial y las siguientes lo evolucionan hasta el esquema actual. En una actualización, la tabla `flyway_schema_history` permite ejecutar únicamente las pendientes y comprobar los nombres y checksums de las ya aplicadas.

Los números de migración identifican la evolución del **esquema**, no necesariamente la versión del programa. La versión 1.4.1 ya publicó las migraciones `V1_5_0`, `V1_5_0_1` y `V1_5_0_2`; se mantienen sus nombres y contenidos para conservar la compatibilidad. La versión 1.4.2 añade las migraciones `V1_5_0_3` a `V1_5_0_6` para la limpieza manual, sus reintentos y la elección independiente del borrado del registro de EPL Sync, sin modificar las anteriores. Se mantienen también estos nombres para instalaciones de pruebas que ya las hayan aplicado.

Una instalación limpia no utiliza automáticamente una instantánea consolidada. Ese comportamiento exigiría preparar y mantener expresamente una migración de base o un mecanismo de baseline apropiado. No hay que renombrar, consolidar ni modificar las migraciones publicadas, ni reparar checksums para ocultar una modificación.


La versión 1.5.0 de la aplicación utiliza el mismo esquema `1.5.0.6` que la 1.4.2.
No añade migraciones ni renumera las anteriores. Al actualizar desde 1.4.2, Flyway
valida el historial existente y conserva los datos sin ejecutar migraciones nuevas.

La versión 1.6.0 añade cinco migraciones sobre la 1.5.0 (`V1_5_0_7` a
`V1_5_0_11`), hasta el esquema `1.5.0.11`. Conserva los datos existentes,
los trabajos y sus vínculos; las nuevas columnas reciben valores predeterminados.
Las migraciones publicadas mantienen sus nombres y checksums. La actualización
se verifica con los scripts exactos de la 1.5.0 y datos de catálogo, usuarios,
Torrent, Calibre, tareas, eventos y operaciones pendientes. Conserva los datos
persistentes y la clave de cifrado, y realiza una copia de seguridad antes de
actualizar.

## Coordinación con SQLite

Las transacciones de escritura y las actualizaciones JDBC comparten una cola
ordenada antes de obtener una conexión. Las consultas de lectura no entran en
esa cola. Las llamadas de red a Torrent y Calibre se realizan fuera de las
transacciones SQL.

Las sincronizaciones esperan su turno, con un máximo de 30 segundos para iniciar
la operación. La sincronización de Calibre se coordina con los envíos y borrados
para publicar una biblioteca completa. Una ocupación temporal del cliente Torrent
o de Calibre aplaza los borrados y se reintenta automáticamente; los conflictos
reales de identidad, archivos compartidos o trabajos dependientes mantienen sus
protecciones. Los borrados ya enviados solo se verifican tras un reinicio.

La versión 1.6.1 añade el borrado manual del historial de trabajos mediante `V1_5_0_12`, sin modificar las
migraciones publicadas. Crea una tabla de identificadores de solicitudes ya
eliminadas para impedir su repetición; no altera los trabajos ni las operaciones
pendientes existentes. La actualización desde el esquema de la 1.6.0 conserva
los datos y solo aplica esa migración adicional.


La asignación de identificadores ausentes en Calibre añade `V1_5_0_13`, sin
modificar migraciones publicadas. Conserva los trabajos existentes y su identidad
EPUB original; añade el UUID de Calibre y el seguimiento de la asignación y su
confirmación. Los envíos antiguos reciben valores vacíos y no se modifican de
forma automática por aplicar la migración.

## Ejecución directa en Windows, Linux y macOS

Las [instrucciones del JAR](../README.md#ejecutar-sin-docker) utilizan Java 25 y
el mismo archivo en las tres plataformas, sin instalar Node.js, Maven ni SQLite.
En macOS, selecciona Java aarch64 para Apple Silicon o x64 para Intel. El
controlador SQLite incluido contiene bibliotecas nativas para ambas arquitecturas.
El comando de arranque incluye `--enable-native-access=ALL-UNNAMED`.

Ejecuta desde la carpeta que contiene el JAR y `application.yaml`; las rutas
relativas de datos y logs parten de ella. Conserva esos archivos y la clave de
cifrado al actualizar. No se utiliza el `.env` de Docker Compose.

Para acceder a los EPUB, configura las raíces remota y local de Torrent con las
rutas reales del cliente y del equipo que ejecuta EPL Sync, respectivamente. En
macOS, utiliza rutas como `/Users/tuusuario/Downloads/Libros` y permite su lectura
al proceso Java. qBittorrent y el servidor de contenido de Calibre siguen siendo
servicios opcionales independientes.

La versión 1.6.2 aplica `V1_5_0_13` al actualizar desde la 1.6.1, hasta el esquema
`1.5.0.13`, conservando los datos y el historial existente. Las migraciones ya
publicadas no se modifican ni se renombran.

La versión 1.6.3 mantiene el esquema `1.5.0.13`: al actualizar desde la 1.6.2 no
se aplican nuevas migraciones. Se conservan los datos, trabajos y configuraciones.

## Instalar como aplicación (PWA)

EPL Sync puede instalarse desde un navegador compatible y abrirse en una ventana
independiente, sin la barra de direcciones ni las pestañas habituales. Utiliza el
icono de instalación de la barra de direcciones o la opción **Instalar EPL Sync**
del menú del navegador. El texto y la disponibilidad dependen del navegador y del
sistema operativo. En iPhone/iPad utiliza **Añadir a pantalla de inicio** desde
el menú de compartir cuando esté disponible.

La instalación requiere **HTTPS con un certificado válido y reconocido por el
dispositivo**. Las direcciones locales `http://localhost:8088` y
`http://127.0.0.1:8088` son excepciones admitidas para pruebas en el mismo equipo.
Una dirección de red como `http://192.168.1.50:8088` no cumple este requisito:
configura HTTPS mediante un [proxy inverso](seguridad.md#acceso-remoto) y accede
siempre por esa dirección HTTPS. En un móvil, `localhost` se refiere al propio
móvil, no al servidor donde está EPL Sync.

Los navegadores basados en Chromium ofrecen instalación en equipos de escritorio;
otros navegadores y plataformas pueden ofrecer opciones distintas o únicamente
accesos directos. Si no aparece la opción, comprueba HTTPS, si la aplicación ya
está instalada y la compatibilidad del navegador. Puedes seguir usando EPL Sync
desde una pestaña normal.

**No hay funcionamiento sin conexión:** el servidor debe estar encendido y
accesible. No se registra ningún service worker ni se añade caché para uso sin
conexión. La aplicación instalada utiliza la misma autenticación y los mismos
permisos que la web; instalarla no concede permisos adicionales. La instalación
está asociada a la dirección del servidor y al perfil del navegador. Desinstalarla
no elimina la base de datos ni los trabajos del servidor.

Consulta los [requisitos de instalación de PWA en MDN](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Making_PWAs_installable).

La versión 1.7.0 añade ocho migraciones desde la 1.6.3, hasta el esquema
`1.7.0.7`. Conserva los datos y los SQL publicados. Incorpora búsquedas guardadas
personales, preparación de limpiezas y envíos grandes en segundo plano,
seguimiento de descargas, cancelación relacionada e índices para los borrados.
Los trabajos de descarga históricos terminados o cancelados siguen cerrados;
los activos adquieren seguimiento y los pausados por el usuario se conservan
pausados. La actualización se comprueba con los SQL exactos de 1.6.3, incluidos
su historial Flyway y sus datos. Consulta las [notas de 1.7.0](releases/1.7.0.md).

La versión 1.7.1 mantiene el esquema `1.7.0.7`. La actualización desde 1.7.0
no aplica nuevas migraciones y conserva los datos, trabajos y ajustes.
Consulta las [notas de 1.7.1](releases/1.7.1.md).

La versión 1.8.0 mantiene el esquema `1.7.0.7`. Desde 1.7.1 no hay nuevas
migraciones: los ajustes e identificadores personales de Hardcover utilizan
la tabla existente `app_settings`. Las API keys guardadas se cifran con
`EPLSYNC_SECRET_KEY`; conserva esa clave junto a las copias de seguridad.
Consulta las [notas de 1.8.0](releases/1.8.0.md).
