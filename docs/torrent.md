# Integración con qBittorrent

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Conexión con clientes torrent

La integración permite comprobar la conexión, enviar un libro por su EPL Id
y renombrar explícitamente torrents existentes. La integración está deshabilitada
por defecto y no conecta al arrancar; el catálogo funciona aunque qBittorrent
esté apagado. Las conexiones se realizan al solicitar una operación torrent.

## Configuración

Las propiedades comunes están en `eplsync.torrent` en `application.yaml`, con valores
literales y sin placeholders de entorno. Spring permite sobrescribirlas mediante
su binding nativo de variables de entorno, también desde Docker (los puntos pasan
a guiones bajos y los guiones se eliminan):

| Variable | Predeterminado | Función |
| --- | --- | --- |
| `EPLSYNC_TORRENT_ENABLED` | `false` | Activa la integración |
| `EPLSYNC_TORRENT_CLIENT` | `qbittorrent` | Adaptador seleccionado (actualmente solo qBittorrent) |
| `EPLSYNC_TORRENT_BASEURL` | `http://localhost:8080` | URL de la WebUI, incluyendo protocolo, puerto y posible ruta del proxy |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_MODE` | `auto` | `auto`, `api-key` o `session` |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_APIKEY` | Vacío | Clave configurada en qBittorrent (compatible con 5.2.3) |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_USERNAME` | Vacío | Usuario de la WebUI |
| `EPLSYNC_TORRENT_QBITTORRENT_AUTH_PASSWORD` | Vacío | Contraseña de la WebUI |
| `EPLSYNC_TORRENT_CONNECTTIMEOUT` | `5s` | Tiempo máximo para establecer la conexión |
| `EPLSYNC_TORRENT_REQUESTTIMEOUT` | `10s` | Tiempo máximo por petición HTTP, no por toda la comprobación |
| `EPLSYNC_TORRENT_RENAME_ENABLED` | `true` | Habilita el renombrado del nombre mostrado del torrent |
| `EPLSYNC_TORRENT_RENAME_PATTERN` | `EPL_{eplId}_{title}` | Patrón; admite los nombres de campo de `CatalogBook`, incluido `{revision}` |

Para qBittorrent, la URL base no incluye `/api/v2`: su adaptador lo añade, conservando la ruta base.
Por ejemplo, `https://torrent.example.com/qbit/` consulta
`https://torrent.example.com/qbit/api/v2/app/version`. Se admiten HTTP y HTTPS
con validación TLS normal. No se siguen redirecciones: debe configurarse la URL
final de la WebUI, no una página de login de un proxy.

La autenticación específica se configura en `eplsync.torrent.qbittorrent.auth`.
En su modo `auto`, se prueba primero la API key, si existe. Solo si la respuesta es un
rechazo de autenticación (`401`/`403`) se intenta sesión, siempre que estén
configurados usuario **y** contraseña. Si solo hay un método completo, se usa ese.
Los modos explícitos usan exclusivamente su método, aunque haya otras credenciales.
Un error de red, timeout, redirección, respuesta inesperada o fallo del servidor
no provoca cambio de método. Un rechazo también puede proceder de las restricciones
de acceso de qBittorrent o del proxy; no siempre significa contraseña incorrecta.

La API key se envía como Bearer. El modo sesión inicia sesión con las credenciales
de la WebUI y conserva en memoria las cookies recibidas, incluyendo los nombres
`QBT_SID_*` de 5.2.3 y `SID` de versiones anteriores. También admite login `204`
sin cuerpo y el antiguo `200 Ok.`. Cuando una sesión es rechazada, se realiza
como máximo un nuevo login por comprobación. Las cookies no se guardan en disco.
Las respuestas de nuestra API no exponen URL, credenciales, cookies ni cuerpos de
error remotos.

Si la integración está habilitada, una configuración inválida impide el arranque:
URL incorrecta, timeouts no positivos
o patrón de rename inválido. El patrón solo se valida cuando rename está habilitado.
Las credenciales incompletas o inválidas se validan al usar la integración, no al
arrancar: la aplicación y el catálogo siguen disponibles. La comprobación devuelve
`503` si falta configuración de autenticación, o `502` si el servidor rechaza las
credenciales. No se intenta conectar durante el arranque.
Cambiar la configuración requiere reiniciar la aplicación.

## Docker y ejecución local

Crear un archivo `.env` con las variables de conexión y activar
`EPLSYNC_TORRENT_ENABLED=true`. Como alternativa al bloque `environment` actual, puedes configurar `env_file`
en el servicio Compose para transmitir todas las variables del archivo:

```yaml
services:
  eplsync:
    # Conservar build, puertos y volúmenes del servicio actual.
    env_file:
      - .env
```

Si ambos contenedores comparten red Docker, `qbittorrent` puede ser el nombre del
servicio y `8080` su puerto **interno**. También se puede usar una URL pública
HTTPS. `localhost` desde el contenedor apunta al propio contenedor de EPL Sync.
El archivo `.env` está excluido de Git; no incluir credenciales reales en YAML
versionado. Fuera de Docker se deben exportar las variables al proceso Java:
Spring no carga `.env` automáticamente. También se pueden montar secretos como
archivos mediante la configuración `configtree` de Spring.

## Comprobar la conexión

```sh
curl http://localhost:8088/api/torrent/client/connection
```

La comprobación consulta `app/version` y `app/webapiVersion`; en modo sesión también
puede llamar a `auth/login`. No añade, borra, inicia, detiene ni renombra torrents.
Una respuesta correcta (`200`, `Cache-Control: no-store`) tiene esta estructura:

```json
{
  "enabled": true,
  "connected": true,
  "client": "qbittorrent",
  "authMode": "api-key",
  "version": "v5.2.3",
  "apiVersion": "2.15.1"
}
```

Las versiones son las devueltas por el servidor. Deshabilitado devuelve `200`,
`enabled: false`, `connected: false`, `client` con el adaptador configurado y
`authMode`, `version`, `apiVersion` a `null`, sin tráfico
hacia qBittorrent. Los errores usan el formato `ErrorResponse` de la API:

- `502`: autenticación rechazada, error de conexión o respuesta remota inválida.
- `504`: timeout.
- `503`: configuración de autenticación incompleta/inválida o comprobación interrumpida.

Las pruebas usan un servidor HTTP local simulado y SQLite en memoria; no requieren
un qBittorrent real ni credenciales y no modifican descargas.


## Nombre del torrent a partir del libro

`eplsync.torrent.rename.pattern` utiliza los nombres de los campos Java de
`CatalogBook`, respetando mayúsculas y minúsculas. Se descubren automáticamente
sus campos de instancia con getter; no se mantiene una lista duplicada. Por ejemplo:

```yaml
rename:
  enabled: true
  pattern: "{author} - {title} [{eplId}] (r{revision})"
```

Con los datos correspondientes, genera:

```text
Bronte, Charlotte - Jane Eyre [2663] (r1.2)
```

Reglas de resolución:

- Corchetes, paréntesis, guiones y demás texto fuera de `{campo}` son literales.
- Campos nulos se sustituyen por texto vacío. La puntuación del patrón se conserva.
- Fechas se muestran como `YYYY-MM-DD`; `language` como su código (`es`, `en`, etc.) y los demás enums como su nombre.
- Números usan punto decimal, sin ceros decimales innecesarios: `1.2`, `2`, `1.5`.
- Los valores se insertan literalmente, sin volver a interpretar llaves, `$` o
  barras presentes en el título o autor. Los caracteres de control y separadores
  de línea se convierten en espacios; se eliminan espacios exteriores del resultado.
- Se rechazan campos inexistentes, llaves mal formadas y resultados vacíos.
  No se admiten expresiones, llamadas a métodos ni rutas como `{language.name}`.

Para aplicar el patrón a **un torrent ya existente** en el cliente seleccionado:

```http
POST /api/catalog/books/{eplId}/torrents/{hash}/rename
```

No requiere cuerpo. El hash debe pertenecer a `links` de ese libro. Se admiten
hexadecimal de 40 caracteres y Base32 de 32, normalizado a hexadecimal. La operación
es explícita por hash, también cuando el libro tiene varios torrents. Devuelve
`eplId`, `hash`, `name` y `client` únicamente cuando el cliente confirma el cambio.

qBittorrent recibe `POST /api/v2/torrents/rename` con `hash` y `name`. No se llama a
`renameFile` ni `renameFolder`: cambia únicamente la columna **Name**, conservando
los nombres y rutas de los archivos. Primero se comprueba la autenticación con
lecturas; la petición de renombrado no se reintenta automáticamente ante errores o
timeouts, ya que el servidor podría haber aplicado el cambio.

- `400`: hash inválido, no pertenece al libro o el patrón produce un nombre vacío.
- `404`: libro ausente del catálogo o torrent ausente de qBittorrent.
- `409`: conexión/rename deshabilitados o nombre rechazado por qBittorrent.
- `422`: el adaptador no implementa renombrado exclusivo del nombre del torrent.
- `502`/`504`/`503`: mismos errores de comunicación que en la comprobación de conexión.

Cambiar el patrón no modifica descargas automáticamente. Los endpoints de magnets
mantienen su `dn` actual; ese nombre sugerido es independiente del nombre aplicado
al cliente por esta operación.

## Enviar un libro a qBittorrent

`POST /api/torrent/books/{eplId}` crea un trabajo de un libro, igual que el envío
por filtros crea un trabajo para uno o varios libros. La API exige `dryRun` y admite
`options` con las opciones del torrent; sin `options` conserva los valores del servidor.

```sh
curl -X POST http://localhost:8088/api/torrent/books/2663 \
  -H 'Content-Type: application/json' \
  -d '{"dryRun":false,"options":{"start":false,"savePath":"/downloads/libros","qbittorrent":{"autoManagement":false,"category":"Libros","tags":[]}}}'
```

Devuelve `202 Accepted`, `jobId` y `Location`; el trabajo registra progreso y resultado.
Si hay varios hashes, indica `options.hash` para elegir uno. `dryRun:true` prepara el
libro sin guardar ni enviar y devuelve un resumen. No comprueba el estado remoto.
Los errores remotos (categoría inexistente, autenticación, etc.) se reflejan al ejecutar
el trabajo. Las opciones omitidas heredan los valores del servidor; `category:""`,
`tags:[]` y `savePath:""` permiten vaciar cada opción. Una ruta personalizada requiere
`autoManagement:false` y pertenece al equipo/contenedor de qBittorrent.

`GET /api/torrent/options` permite consultar los valores efectivos para rellenar la
vista Enviar. No expone credenciales ni consulta preferencias remotas del cliente.

#### Placeholders en etiquetas

Los tags del YAML y de la petición admiten campos de `CatalogBook`, igual que el
renombrado: `["EPLSync", "{language}"]` produce `["EPLSync", "es"]` para un
libro en español. También se admiten prefijos, por ejemplo `"idioma:{language}"`.
Los campos se resuelven una sola vez, conservando literalmente sus valores.

Se rechazan campos desconocidos y llaves mal formadas antes de contactar con qBit.
Los campos nulos se convierten en texto vacío; se omiten los resultados vacíos,
se recortan espacios exteriores y se eliminan duplicados conservando el orden.
Cada entrada representa una etiqueta: los resultados con comas o caracteres de
control se rechazan, también si proceden de campos como `genres` o `author`.
Las etiquetas de la petición sustituyen la lista del YAML; `[]` envía sin etiquetas.
qBittorrent crea las etiquetas nuevas al añadir el torrent. Un torrent ya existente
conserva sus etiquetas.

