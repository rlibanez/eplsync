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

Todos los datos persistentes se conservan en `./data`, montada en `/data`:
`db/` contiene SQLite (incluidos sus archivos WAL y SHM) y `logs/` los logs. No hace falta crear
las carpetas antes del primer inicio: Docker Compose las crea si faltan y el contenedor arranca
brevemente como root para asignarles el propietario `PUID:PGID` y permisos de
lectura, escritura y acceso para ese usuario. `DATA_DIR` permite elegir la carpeta
del host. Después ejecuta Java con `PUID:PGID` en el mismo contenedor.
Si fuerzas un usuario con `user:` o `docker run --user`, debes preparar los
permisos previamente, porque ese usuario no puede cambiar el propietario.

Consulta los identificadores de tu usuario para configurar `.env`:

```sh
id -u
id -g
```

Utiliza los identificadores de tu usuario en `PUID` y `PGID` si las carpetas le
pertenecen. Para asignar carpetas existentes a otros identificadores, por ejemplo
`1000:1000`:

```sh
sudo chown -R 1000:1000 data
```

Adapta los números y las rutas a tu configuración. Cambiar `PUID` o `PGID` no
modifica automáticamente el propietario de los archivos existentes, solo el de
la raíz de datos y sus subcarpetas `db/` y `logs/`. En sistemas que impiden cambiar el propietario (por
ejemplo, algunos montajes NFS), prepara los permisos en el servidor de almacenamiento.

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

La versión 1.8.1 mantiene el esquema `1.7.0.7`. La actualización desde 1.8.0
no aplica nuevas migraciones y conserva los datos, trabajos y ajustes.
Consulta las [notas de 1.8.1](releases/1.8.1.md).

La versión 1.8.2 incorpora cinco migraciones desde 1.8.1 para resolver u omitir
incidencias de Calibre, conservar las actuaciones de limpieza y separar la
Limpieza de su Actualización de origen. Conserva los datos y los trabajos.
El esquema resultante es `1.8.3.2`; se mantienen los identificadores de migración
existentes aunque no coincidan con la versión de la aplicación.
Consulta las [notas de 1.8.2](releases/1.8.2.md).

La protección de preparación por lotes de Torrent añade la columna `preparing`
a los trabajos de descarga (migración `1.8.3.3`). Los trabajos existentes conservan
sus estados y datos. Una preparación interrumpida se cancela sin enviar sus
elementos; los trabajos que ya estaban activos conservan su recuperación habitual.

La migración `V1_8_3_4` añade el inventario de archivos de Descargas y los datos de
verificación y formatos permitidos de los envíos de Calibre. Conserva registros,
trabajos, reintentos y estados existentes; sus envíos pendientes siguen usando
EPUB por defecto. Los inventarios existentes comienzan «Sin comprobar» y se
completan en segundo plano. No es necesario vaciar la base de datos.

`V1_8_3_5` añade los formatos conocidos a la copia local de la biblioteca de Calibre.
Conserva las entradas anteriores; su formato queda sin comprobar hasta sincronizar
la biblioteca. La actualización no cambia archivos ni metadatos en Calibre.

`V1_8_3_6` añade los plazos persistentes de reconciliación y reintento de los elementos
de Torrent e índices para la cola de envíos y los inventarios descargados. Conserva
los trabajos, los archivos y el historial existentes. Los envíos que estaban en
curso se recuperan comprobando su presencia antes de volver a incorporarlos.

La versión 1.8.3 incorpora cinco migraciones desde 1.8.2 y alcanza el esquema
`1.8.3.7`. Conserva los trabajos, las descargas, los ajustes y el historial. Los
archivos de los torrents existentes se inventarían gradualmente en segundo plano,
cediendo ante los envíos. Consulta las [notas de 1.8.3](releases/1.8.3.md).

## Actualización a 1.8.4

La versión 1.8.4 añade dos migraciones (`1.8.3.8` y `1.8.3.9`) sobre 1.8.3
y alcanza el esquema `1.8.3.9`.
Se conservan sus nombres y las migraciones publicadas. La primera registra la
intención de envío para distinguir las confirmaciones recuperadas de los torrents
preexistentes; la segunda guarda la apariencia por usuario. No se reclasifican
los resultados antiguos que carecen de información de origen.

`V1_8_3_8` registra la intención de envío de cada elemento antes de contactar con
qBittorrent, para clasificar como aceptadas sus confirmaciones tras reintentos o
reinicios. No altera los resultados históricos ni presume el origen de envíos
anteriores que no disponían de esa información.

`V1_8_3_9` añade `user_appearance` para guardar el tema y la paleta por usuario.
La apariencia local se adopta en el primer inicio de sesión si no existe una
preferencia guardada; la actualización no asigna preferencias a otras cuentas.

Los datos y logs se organizan ahora bajo una única raíz: `db/` para SQLite y
`logs/` para los registros. En Docker, `DATA_DIR` se monta en `/data` y desaparece
el montaje separado `LOGS_DIR`. Para el JAR se utiliza `data/` en el directorio de
ejecución, salvo que se indique `EPLSYNC_DATA_DIR`.

Si actualizas una instalación con datos, detén la aplicación y conserva una copia
de seguridad junto con `EPLSYNC_SECRET_KEY`. Antes de arrancar, coloca la base
`eplsync.db` y los archivos auxiliares SQLite que se conserven en `db/`, y los logs
en `logs/` dentro de la raíz elegida. Sustituye o adapta Compose y el YAML externo:
las rutas antiguas personalizadas no se trasladan automáticamente. No arranques
con la nueva ruta vacía si quieres conservar tu biblioteca, cuentas y trabajos.

Consulta las [notas de 1.8.4](releases/1.8.4.md).

## Actualización a 1.8.5

Desde 1.8.4, EPL Sync aplica las migraciones `1.8.4.1` y `1.8.4.2`
en su base de datos SQLite para guardar los identificadores de Calibre y
las revisiones de Hardcover. Conserva una copia de seguridad antes de actualizar.
Consulta las [notas de 1.8.5](releases/1.8.5.md).
