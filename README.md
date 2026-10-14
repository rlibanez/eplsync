<p align="center">
  <img src="frontend/public/brand/eplsync-app-icon-192.png" alt="Logo de EPL Sync" width="112" height="112">
</p>
<h1 align="center">EPL Sync</h1>

Consulta el catálogo de ePubLibre y gestiona tus descargas y actualizaciones de
libros con qBittorrent.

<table>
  <tr>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Principal.png"><img src="docs/media/screenshots/Principal.png" alt="Pantalla inicial" width="320"></a>
      <br><sub>Pantalla inicial</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Catalogo.png"><img src="docs/media/screenshots/Catalogo.png" alt="Catálogo" width="320"></a>
      <br><sub>Catálogo</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Catalogo_BusquedaAvanzada.png"><img src="docs/media/screenshots/Catalogo_BusquedaAvanzada.png" alt="Búsqueda avanzada" width="320"></a>
      <br><sub>Búsqueda avanzada</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Catalogo_Libro1.png"><img src="docs/media/screenshots/Catalogo_Libro1.png" alt="Ficha del libro" width="320"></a>
      <br><sub>Ficha del libro</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Catalogo_Libro2.png"><img src="docs/media/screenshots/Catalogo_Libro2.png" alt="Ficha del libro · descargas y Calibre" width="320"></a>
      <br><sub>Ficha del libro · descargas y Calibre</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Directorio_Colecciones.png"><img src="docs/media/screenshots/Directorio_Colecciones.png" alt="Directorio de colecciones" width="320"></a>
      <br><sub>Directorio de colecciones</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Descargas_Estado.png"><img src="docs/media/screenshots/Descargas_Estado.png" alt="Estado de las descargas" width="320"></a>
      <br><sub>Estado de las descargas</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Descargas_Actualizaciones.png"><img src="docs/media/screenshots/Descargas_Actualizaciones.png" alt="Actualizaciones de descargas" width="320"></a>
      <br><sub>Actualizaciones de descargas</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Descargas_Trabajos.png"><img src="docs/media/screenshots/Descargas_Trabajos.png" alt="Trabajos de descargas" width="320"></a>
      <br><sub>Trabajos de descargas</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Calibre_Biblioteca.png"><img src="docs/media/screenshots/Calibre_Biblioteca.png" alt="Biblioteca de Calibre" width="320"></a>
      <br><sub>Biblioteca de Calibre</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Calibre_Actualizaciones.png"><img src="docs/media/screenshots/Calibre_Actualizaciones.png" alt="Actualizaciones de Calibre" width="320"></a>
      <br><sub>Actualizaciones de Calibre</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Calibre_Trabajos.png"><img src="docs/media/screenshots/Calibre_Trabajos.png" alt="Trabajos de Calibre" width="320"></a>
      <br><sub>Trabajos de Calibre</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Eventos.png"><img src="docs/media/screenshots/Eventos.png" alt="Eventos" width="320"></a>
      <br><sub>Eventos</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Ajustes_UsuarioSeguridad.png"><img src="docs/media/screenshots/Ajustes_UsuarioSeguridad.png" alt="Usuarios y seguridad" width="320"></a>
      <br><sub>Usuarios y seguridad</sub>
    </td>
    <td align="center" width="33%">
      <a href="docs/media/screenshots/Ajustes_VerdeClaro.png"><img src="docs/media/screenshots/Ajustes_VerdeClaro.png" alt="Ajustes · tema verde claro" width="320"></a>
      <br><sub>Ajustes · tema verde claro</sub>
    </td>
  </tr>
</table>

## Contenido

- [Instalación con Docker](#instalación-con-docker)
  - [Requisitos](#1-requisitos)
  - [Prepara la carpeta de instalación](#2-prepara-la-carpeta-de-instalación)
  - [Descarga Docker Compose](#3-descarga-docker-compose)
  - [Configura `.env`](#4-configura-env)
  - [Prepara las carpetas persistentes](#5-prepara-las-carpetas-persistentes)
  - [Inicia EPL Sync](#6-inicia-epl-sync)
- [Ejecutar sin Docker](#ejecutar-sin-docker)
  - [Descarga y descomprime el paquete](#1-descarga-y-descomprime-el-paquete)
  - [Configura la aplicación](#2-configura-la-aplicación)
  - [Inicia EPL Sync](#3-inicia-epl-sync)
  - [Abre la aplicación](#4-abre-la-aplicación)
- [Primer uso](#primer-uso)
- [Actualizar la instalación con Docker](#actualizar-la-instalación-con-docker)
- [Documentación y ayuda](#documentación-y-ayuda)

## Instalación con Docker

### 1. Requisitos

Necesitas Docker y Docker Compose v2. Consulta la
[documentación oficial para instalar Docker](https://docs.docker.com/get-started/get-docker/).

### 2. Prepara la carpeta de instalación

```sh
mkdir -p eplsync
cd eplsync
```

### 3. Descarga Docker Compose

```sh
curl -fsSL https://raw.githubusercontent.com/rlibanez/eplsync/v1.2.0/docker-compose.yml -o docker-compose.yml
```

### 4. Configura `.env`

Después, descarga la plantilla de configuración:

```sh
curl -fsSL https://raw.githubusercontent.com/rlibanez/eplsync/v1.2.0/.env.example -o .env
chmod 600 .env
```

Edita `.env`. Para empezar, revisa estas variables:

| Variable | Descripción | Valor predeterminado |
| --- | --- | --- |
| `EPLSYNC_VERSION` | Versión de la imagen. | `1.2.0` |
| `PUID` | ID del usuario que puede escribir en las carpetas. En Linux, consulta su valor con `id -u`. | `1000` |
| `PGID` | ID del grupo que puede escribir en las carpetas. En Linux, consulta su valor con `id -g`. | `1000` |
| `HOST_PORT` | Puerto publicado. | `8088` |
| `DATA_DIR` | Carpeta persistente de datos. | `./data` |
| `LOGS_DIR` | Carpeta persistente de logs. | `./logs` |
| `TZ` | Zona horaria. | `Europe/Madrid` |
| `EPLSYNC_SECRET_KEY` | Necesaria para guardar credenciales de qBittorrent desde Ajustes. Genera su valor con `openssl rand -base64 32`, cópialo en `.env` y consérvalo. | Sin valor |
| `EPLSYNC_INITIAL_ADMIN_KEY` | Clave opcional para proteger la creación del primer administrador. Genera su valor con `openssl rand -hex 16`. Vacía no exige clave. | Sin valor |

El resto puede conservar sus valores predeterminados, aunque es muy recomendable revisarlos.<br>
Si necesitas personalizar otras opciones, consulta la [tabla de variables](docs/variables-entorno.md).<br>
Los ajustes disponibles en la interfaz pueden configurarse desde **Ajustes**: los valores allí guardados tienen
prioridad sobre los valores iniciales del entorno.<br>
**Importante**: no todas las variables del archivo `.env` se pueden configurar desde **Ajustes**.

### 5. Prepara las carpetas persistentes

```sh
mkdir -p data logs
```

Si has cambiado `DATA_DIR` o `LOGS_DIR`, crea las carpetas correspondientes.
Deben permitir escritura al usuario y grupo indicados en `PUID` y `PGID`.

Para descargar EPUB o enviarlos a Calibre desde el cliente torrent, configura
`TORRENT_DIR` con la carpeta de descargas de qBittorrent en el host y descomenta
su montaje en `docker-compose.yml`. Configura también la carpeta base vista por
qBittorrent en `EPLSYNC_TORRENT_FILES_REMOTE_ROOT` o en Ajustes > Torrent
(por ejemplo, `/downloads`). Definir `TORRENT_DIR` por sí solo no activa el montaje.

### 6. Inicia EPL Sync

```sh
docker compose up -d
```

Abre `http://localhost:8088`, o la dirección y puerto que hayas configurado.
Para acceso por red o mediante un proxy HTTPS, consulta la
[guía de acceso y seguridad](docs/seguridad.md#acceso-remoto).

## Ejecutar sin Docker

Se recomienda encarecidamente la instalación en Docker. Aún así, se proporcionan
instrucciones para su ejecución en Windows/Linux.

### 1. Descarga y descomprime el paquete

Instala [Java 25](https://adoptium.net/temurin/releases/?version=25) y descarga
`EPL-Sync-1.2.0.zip` desde los archivos de la
[Release 1.2.0](https://github.com/rlibanez/eplsync/releases/tag/v1.2.0).
El mismo paquete sirve para Windows y Linux.

Descomprímelo en una carpeta dedicada a EPL Sync. Incluye el JAR y el
`application.yaml` correspondiente a esa misma versión, además de las carpetas
`data` y `logs`:

```text
EPL-Sync/
├── eplsync-1.2.0.jar
├── application.yaml
├── data/
└── logs/
```

### 2. Configura la aplicación

Edita el `application.yaml` incluido en el paquete. Es una copia del mismo archivo
utilizado para construir la aplicación.

Antes de iniciar, revisa estos valores:

- **Acceso local:** añade `address: 127.0.0.1` dentro del apartado `server`, con la
  misma indentación que `port`. Sin esa opción, escucha en todas las interfaces.
- **qBittorrent:** si está en el mismo equipo, cambia `eplsync.torrent.base-url` a
  `"http://localhost:8080"`. También puedes configurarlo desde **Ajustes → Torrent**.
- **Clave de cifrado:** para guardar credenciales desde Ajustes, configura
  `eplsync.security.secret-key`. La interfaz permite generar una clave; cópiala
  en este archivo y reinicia la aplicación.
- **Clave inicial:** `eplsync.security.initial-admin-key` es opcional y protege la
  creación del primer administrador.

Las expresiones como `${EPLSYNC_SECRET_KEY:}` permiten utilizar variables del
entorno. Para configurar una clave directamente en el archivo, sustituye toda
la expresión por el valor entre comillas, por ejemplo `secret-key: "CLAVE_GENERADA"`.
Conserva la configuración y no compartas sus claves.

### 3. Inicia EPL Sync

Abre una terminal en la carpeta del JAR y ejecuta, tanto en Windows como en Linux:

```sh
java --enable-native-access=ALL-UNNAMED -jar eplsync-1.2.0.jar --spring.config.additional-location=file:./application.yaml
```

El comando carga el archivo externo de configuración. En esta modalidad no se
utiliza el archivo `.env` de Docker Compose.

### 4. Abre la aplicación

Accede a **http://localhost:8088** y sigue los pasos de [Primer uso](#primer-uso).
Mantén la terminal abierta. Para detener EPL Sync, pulsa **Ctrl+C**.

Para actualizar, detén la aplicación y descarga el paquete de la nueva Release.
Sustituye el JAR y adapta su nombre en el comando de arranque. Conserva `data/`,
`logs/` y tu configuración; revisa el YAML de la nueva versión para incorporar
las opciones nuevas sin perder tus claves ni valores personalizados. Los ajustes
guardados desde la interfaz tienen prioridad.

## Primer uso

1. Abre `http://localhost:8088` o la dirección y puerto que hayas configurado.
2. Crea la primera cuenta de administrador con nombre de usuario, correo
   electrónico, contraseña y, si está configurada, la clave de configuración inicial `EPLSYNC_INITIAL_ADMIN_KEY`.
3. Sigue el asistente para importar el Catálogo, configurar y sincronizar
   qBittorrent y conectar la biblioteca de Calibre. Torrent y Calibre son
   opcionales: puedes omitirlos.

Puedes continuar más tarde y retomar el asistente desde
**Ajustes → General → Abrir asistente de configuración**. Solo se abre
automáticamente en instalaciones nuevas; una actualización no lo activa.

## Actualizar la instalación con Docker

Cambia `EPLSYNC_VERSION` en `.env` a la versión que quieras instalar y ejecuta:

```sh
docker compose pull
docker compose up -d
```

Conserva las carpetas persistentes y el valor de `EPLSYNC_SECRET_KEY`.
Puedes consultar la versión instalada y buscar actualizaciones en
**Ajustes → Acerca de**.

## Documentación y ayuda

- [Guías de uso](docs/README.md).
