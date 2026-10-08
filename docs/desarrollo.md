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


## Frontend React + TypeScript

El código vive en `frontend/`, separado del proyecto Maven. Requiere Node.js 24
(o una versión compatible con Vite 7) y npm. Desde la raíz:

```sh
npm ci --prefix frontend
npm run dev --prefix frontend
```

Vite muestra su dirección local (normalmente `http://localhost:5173`). Ejecuta
el backend en otro terminal, con Java 25:

```sh
./backend/mvnw -f backend/pom.xml spring-boot:run
```

Vite reenvía `/api/` a `http://localhost:8088`; si cambias el puerto de Spring,
ajusta `frontend/vite.config.ts`. No es necesario habilitar CORS ni configurar
una URL de backend en el navegador. La interfaz usa rutas relativas `/api/`.

```sh
npm test --prefix frontend
npm run build --prefix frontend
```

La compilación comprueba TypeScript y genera `frontend/dist/`. No se versionan
`dist/` ni `node_modules/`; sí se versiona `package-lock.json` para instalaciones
reproducibles. Las pantallas están agrupadas por funcionalidad en
`src/features/`, el marco de navegación en `src/layout/` y el contrato HTTP en
`src/api/`.

La primera versión incluye Inicio, catálogo paginado con filtros y ordenación,
y ficha individual con historial asociado. Los filtros se conservan en la URL.
Las consultas no importan el catálogo ni sincronizan o modifican qBittorrent.
El catálogo no proporciona portadas: se muestra un marcador gráfico local.

## Frontend en Docker

Para construir localmente, copia `docker-compose.override.example.yml` a
`docker-compose.override.yml` si aún no existe. Después,
`REVISION=$(git rev-parse HEAD) docker compose up -d --build`
compila el frontend con Node, copia `dist/` a los
recursos estáticos de Spring y empaqueta todo en el JAR. El contenedor final
solo ejecuta Java y sirve interfaz y API en el puerto 8088:

- `/`: inicio.
- `/catalog`: catálogo.
- `/catalog/32`: ficha de un libro, también accesible directamente.
- `/directory`: autores, idiomas, géneros y años del catálogo.
- `/downloads`: estado local y sincronización manual.
- El envío se inicia desde la selección de `/catalog`; no existe una pantalla independiente de envío.
- `/downloads/jobs` y `/downloads/jobs/{id}`: seguimiento y control de trabajos.
- `/settings/general`: idioma, paleta de colores y modo claro/oscuro.
- `/settings/catalog`: importación, previsualización, actualización y limpieza del catálogo.
- `/settings/database`: reinicio completo de la base de datos (administradores).
- `/settings/torrent`: comprobación manual de conexión con el cliente torrent.
- `/settings` y `/maintenance/catalog`: redirigen a General y Catálogo.
- `/api/**`: API existente y configuración pública de interfaz.

El controlador de navegación solo reenvía las rutas conocidas de la interfaz a
`index.html`: los errores de API y los recursos ausentes no se convierten en HTML.
La compilación Maven por sí sola sigue generando el backend; Docker incorpora
el frontend sin copiar artefactos generados al árbol de fuentes local.
Los volúmenes `data/` y `logs/` mantienen su comportamiento habitual.

### Pruebas de navegador

```sh
cd frontend
npx playwright install chromium
npm run test:e2e
```

Playwright arranca Vite en el puerto 5178 y utiliza respuestas de prueba para
verificar filtros, paginación, ficha, retorno al listado tras recargar, errores y
menú móvil. No requiere ni modifica el catálogo local. Para usar un Chromium
ya instalado, define `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` con su ruta.

## Traducciones de la interfaz

El frontend utiliza `react-i18next` con archivos JSON agrupados por función:

- `frontend/src/locales/es.json`: español.
- `frontend/src/locales/en.json`: inglés y traducción de respaldo.
- `frontend/src/locales/resources.ts`: registro de idiomas y sus nombres nativos.

El selector está únicamente en Ajustes → General. Aplica el
cambio sin recargar ni perder los filtros. La elección manual se guarda en
`localStorage` (`eplsync:language`) y tiene prioridad.

Sin elección manual, se detecta el primer idioma compatible de
`navigator.languages` (o `navigator.language` si la lista está vacía).
Si ninguno está disponible, se usa inglés. Las variantes regionales como
`es-ES` se reconocen. No hay configuración de idioma en Docker ni una
petición al servidor para determinarlo.

Si el navegador bloquea el almacenamiento, la elección manual dura solo durante
la sesión de la aplicación. Ajustes contiene el selector de idioma, sin controles
para restablecer la preferencia ni para plegar el lateral.

Para añadir un idioma:

1. Copia `es.json` a otro archivo, por ejemplo `fr.json`, y traduce sus valores.
2. Conserva las claves y las variables `{{title}}`, `{{count}}`, etc. Los plurales
   usan los sufijos de i18next (`_one`, `_other`; añade los que requiera el idioma).
3. Importa el JSON y añade su entrada en `locales` de `resources.ts`, con su nombre
   nativo. El selector y los recursos se generan desde ese registro.
4. Ejecuta `npm test` y `npm run build` desde `frontend/`. Comprueba también el
   diseño con textos largos. Reconstruye la imagen Docker para distribuirlo.

Usa `useTranslation()` para textos nuevos, con frases completas y variables en
vez de concatenar fragmentos. `useLocale()` centraliza formatos de números,
fechas y etiquetas de estados e idiomas. Las fechas con hora se presentan en la
zona horaria del navegador; las fechas sin hora mantienen el día del catálogo.
Los años e identificadores no llevan separadores de millares. No se traducen
los títulos, autores, géneros ni sinopsis del catálogo, ni se modifican los códigos
enviados a la API. El idioma de la interfaz y el filtro de idioma son independientes.

Las pruebas verifican claves y variables de los idiomas registrados, plurales,
cambio de idioma, persistencia, configuración del despliegue, formatos y selección móvil.

## Mantenimiento y preferencias

«Ajustes → Base de datos → Importar catálogo» abre un asistente con URL editable
(inicialmente la configurada), carga de ZIP local o reutilización del ZIP guardado.
El segundo paso permite previsualizar o actualizar. Usa `GET /api/catalog/import/source`
y `POST /api/catalog/import/run` (JSON para URL/guardado, multipart para archivo local).
Al iniciar, cierra el asistente y muestra progreso y resultado en la sección de importación.

`CatalogImportStore` conserva un único ZIP y un descriptor con SHA-256, fechas y la
previsualización opcional. Los CSV extraídos se eliminan al terminar cada operación.
`eplsync.catalog.import.retention` vale 24h; reutilizar el archivo no renueva la fecha.
Iniciar una nueva fuente elimina el ZIP anterior. Aplicar, Descartar y la X conservan
el ZIP. Aplicar verifica en la transacción que el catálogo no haya cambiado; Recalcular
actualiza el resumen desde el mismo ZIP. La pestaña guarda el token en sessionStorage
para recuperar la previsualización al recargar. El descriptor permite recuperarla tras
reiniciar el proceso si sigue disponible el directorio temporal. La caducidad se revisa
al acceder al archivo y cada minuto. No hay ajustes de número de archivos o directorio.

La acción «Reiniciar base de datos» usa `POST /api/maintenance/reset` con
`{"confirm":true}` después de una confirmación destacada en rojo. Vacía las ocho
tablas de datos en una transacción, incluidos metadatos y eventos. No descarga ni
importa el CSV. Conserva el esquema, la configuración, los logs y las preferencias
del navegador. No modifica torrents ni archivos del cliente.
`/api/catalog/import/reset` sigue limitado al reemplazo del catálogo y no se usa
desde el frontend. «Eliminar ausentes» permite revisar y eliminar solo los libros
que no están en un CSV válido, con un token temporal que congela la lista de candidatos;
el historial de descargas y trabajos se conserva.

Un filtro coordina las peticiones API de esta instancia: el reinicio requiere
acceso exclusivo y devuelve `409` si hay otras peticiones activas. El monitor del
worker y el bloqueo de seguimiento impiden solaparlo con envíos o sincronizaciones;
los envíos pendientes de finalizar también provocan `409`. Tras confirmar la
transacción se descarta la cola en memoria y el frontend limpia sus consultas de
catálogo y fichas. Estos bloqueos son locales al proceso: no permiten compartir
la misma base de datos entre varias instancias.

Se pide confirmar la actualización, se muestran los contadores devueltos por la
API y se invalidan las consultas del catálogo y fichas al terminar. No hay un
porcentaje real disponible: se muestra actividad mientras llega la respuesta.
Se conserva la operación al navegar dentro de la aplicación y se bloquean nuevas
operaciones de importación en esa pestaña mientras está pendiente. No es un job
persistente; recargar/cerrar la pestaña puede perder el resultado aunque el servidor
termine la operación. No se reintentan automáticamente las peticiones POST.

El botón «Plegar menú lateral» está al pie del menú. Los iconos conservan su
posición vertical al plegarlo y hay separadores entre grupos. La preferencia se
guarda en `eplsync:sidebar-collapsed`. El menú móvil conserva su comportamiento
desplegable. No se muestran migas de pan.

Las fichas usan `/catalog/{id}` sin parámetros. El enlace guarda la consulta del
catálogo en el estado de navegación y `sessionStorage` proporciona un respaldo
por pestaña (`eplsync:catalog-search`). «Volver al catálogo» recupera filtros,
página y orden, y restaura el desplazamiento cuando la tabla termina de cargar.
Sin contexto guardado vuelve a `/catalog`. La URL del catálogo sí conserva sus
filtros para permitir compartir búsquedas.

General ofrece diez paletas: verde, azul, violeta, rosa, naranja, cian, índigo,
púrpura, lima y amarillo. El selector Claro/Oscuro se guarda en `eplsync:scheme`
(oscuro por defecto); `eplsync:palette` conserva el color en este navegador. Cambian los colores de
Mantine y los tonos de la interfaz; errores y acciones destructivas mantienen sus
colores semánticos. La configuración del servidor sigue en Docker/Spring.

Torrent consulta `GET /api/torrent/client/connection` únicamente al pulsar
«Comprobar conexión», sin reintentos automáticos. Muestra si está habilitado,
si conecta, cliente, autenticación y versiones, o el error correspondiente.
No inicia descargas ni realiza sincronizaciones.


## Navegación y descargas

El lateral agrupa Biblioteca (Catálogo y Directorio) y Descargas (Estado, Enviar y
Trabajos). El logo abre Inicio; no hay enlace Inicio ni eslogan. Ajustes está al
pie, junto al plegado. General, Base de datos y Torrent son pestañas dentro de
Ajustes, con rutas propias. La etiqueta accesible del idioma se conserva oculta
para evitar repetirla visualmente.

Todas las tablas permiten 10, 20, 50, 100, 200, 500 o 1000 elementos, con 20 por defecto.
Cambiar el tamaño vuelve a la primera página. Directorio consulta valores distintos
paginados en la base de datos y abre el catálogo filtrado. Autores y géneros
compuestos se muestran como están guardados; no se normalizan ni se dividen.

Estado lee historial y resumen locales. La sincronización se realiza solo al
pulsar su botón y muestra los contadores de respuesta. Los registros se enlazan
con sus fichas mediante EPL ID; pueden existir varias revisiones o destinos por
libro. Una finalización registrada no equivale necesariamente al estado actual.

Enviar tiene pestañas Individual y Múltiple. Cuando hay varios hashes se exige
elegir uno. Las
opciones omitidas heredan el servidor; categoría y etiquetas permiten una
sobrescritura vacía explícita. Una ruta manual exige desactivar gestión automática.

La previsualización múltiple consulta el catálogo sin enviar torrents. Se envían
todos los libros que coinciden con los filtros aplicados, independientemente de
la página mostrada en la tabla; sin filtros se requiere marcar el catálogo completo.
Cambiar filtros exige previsualizar otra vez. La selección se recalcula al confirmar,
por lo que no constituye una instantánea reservada. Los envíos desde esta vista requieren confirmación,
no se reintentan automáticamente y advierten sobre resultados inciertos.

Un envío múltiple abre su trabajo. Trabajos refresca la lista cada cinco segundos;
el detalle y sus elementos cada tres segundos mientras no haya terminado. Pausar,
reanudar y cancelar operan sobre envíos de EPL Sync, nunca sobre los torrents ya
añadidos. Cancelar exige confirmación. El aviso de envío en curso sobrevive a la
navegación; cerrar o recargar la pestaña puede perder la respuesta aunque el servidor
termine el envío. El reinicio de base de datos limpia también las consultas de estas
vistas y del directorio.


El lateral desplegado ocupa 190 px. Su botón muestra «Plegar» al estar desplegado
y conserva el nombre accesible y tooltip. Todos los iconos, incluido el logo,
comparten un eje horizontal fijo a 38 px del borde izquierdo en ambos estados. El separador superior de Ajustes solo
se muestra en escritorio con el lateral plegado. «Subir» flota fuera de las tablas
y aparece tras desplazarse más de media pantalla (máximo 400 px), siempre que
exista contenido desplazable; respeta la preferencia de movimiento reducido.
Actualizar vista en Trabajos utiliza el color primario y confirma explícitamente
la carga, éxito o error de la actualización manual. El refresco periódico no
genera avisos de éxito.


En la ficha, «Enviar a descargar» crea un trabajo mediante `POST /api/torrent/books/{id}`
con `{"dryRun":false}` y sin navegación ni confirmación adicional. Si hay varios hashes,
se elige uno y se envía en `options.hash`. Se heredan las demás opciones del servidor.
«Abrir magnet» conserva el enlace al cliente asociado por el navegador.

El popup de envío del Catálogo muestra todos los campos de opciones juntos, con ayudas accesibles
por ratón y teclado. Carga `GET /api/torrent/options` y permite restaurar los valores
predeterminados; editar no cambia la configuración del servidor. Ruta se deshabilita
con gestión automática; patrón se deshabilita sin renombrado. Categoría y etiquetas
vacías se envían explícitamente vacías. La previsualización de selección no crea trabajos;
una selección vacía no permite enviar. El backend rechaza crear un trabajo sin coincidencias.

El título de una fila del catálogo navega dentro de la pestaña actual. Su flecha
abre la ficha completa en un nuevo contexto (`target="_blank"`,
`rel="noopener noreferrer"`), normalmente una pestaña según la configuración del
navegador. Conserva menú lateral y «Volver al catálogo», con URL limpia.


Las tablas incluyen un campo «Página» y un botón «Ir» para saltar directamente
a cualquier página válida (numeración visible desde 1, API desde 0). Conservan
los filtros y el tamaño seleccionado. El selector de tamaño no muestra check
y dispone de espacio para «1000 por página». La ficha incluye «Ver en ePubLibre»
con `https://www.epublibre.org/libro/detalle/{eplId}`, en otra pestaña. El favicon
SVG utiliza el mismo libro abierto del logo provisional.

### Eventos persistentes y notificaciones

**Eventos**, encima de Ajustes, consulta el registro compartido `app_events` en
SQLite. Recoge actualizaciones/reemplazos y eliminación de ausentes del catálogo, comprobaciones
colectivas de portadas (incluidas simulaciones), sincronizaciones aplicadas y el
ciclo de vida de los jobs. Funciona tanto desde la interfaz como desde la API;
el historial se conserva al cerrar el navegador y al reiniciar la aplicación.
El reemplazo del catálogo conserva los eventos. El reinicio completo de la base
de datos sí los elimina, sin registrar su propio evento persistente. También hay
mantenimiento independiente en Ajustes → General → Mantenimiento de eventos.

Se guardan fecha UTC, categoría, acción, resultado, origen, identificador de
operación y un resumen de contadores; no se duplican libros, respuestas completas,
credenciales ni listas de URLs. `EventContext.withOrigin(SCHEDULED, ...)` permite
que un futuro planificador identifique operaciones automáticas. Los jobs conservan
ese origen aunque su ejecución continúe en otro hilo o después de un reinicio.
Esto no añade todavía un planificador.

`EventJournal` inserta los resultados dentro de la transacción de los datos y
avisa al canal SSE después del commit. Los fallos se registran después del rollback.
El envío a navegadores usa hilos virtuales separados, señales agrupadas y un máximo
de 32 conexiones por proceso; un cliente lento no bloquea una importación.
El frontend mantiene una conexión `EventSource` sin consultas periódicas al
historial. La reconexión usa `Last-Event-ID` y reproduce los eventos aún conservados;
al abrir la aplicación se carga el historial sin reproducir notificaciones antiguas.
Los comentarios de mantenimiento de conexión no consultan la base de datos.

Límites predeterminados, configurables en `application.yaml` o en el `.env` de Compose:

```yaml
eplsync:
  events:
    retention:
      max-count: 10000
      max-age-days: 365
```

Ambos deben ser positivos. La limpieza se realiza cada minuto en transacciones de
hasta 500 registros: elimina por antigüedad o por superar el número máximo. Puede
haber un exceso temporal entre limpiezas. El mantenimiento manual permite eliminar
todo, hasta una fecha incluida o un intervalo de días completos en la zona horaria
del navegador, con confirmación. Los eventos nuevos creados durante ese borrado
quedan fuera de su alcance.

Los avisos flotantes duran 6 segundos por defecto, admiten cierre con X y
pausan su temporizador al recibir foco o pasar el cursor. Se muestran hasta tres
a la vez. Los avisos locales —conexión, validación o respuestas de acciones menores—
son transitorios y no crean un segundo historial. Los eventos guardados se traducen
al idioma actual de la interfaz. Los logs técnicos siguen en `eplsync.log`.

Para nuevas operaciones relevantes, usar `EventJournal.run` y `completed` dentro
de la transacción, o `record` para transiciones persistentes de jobs. No registrar
cada fila ni cada tick de progreso. En frontend `meta.backendEvents` evita el
aviso local de éxito de mutaciones cuyo resultado ya llega por SSE. La cabecera
`X-EPLSync-Operation-Id` identifica las operaciones registradas y evita duplicar
el error HTTP como aviso local; los rechazos anteriores a iniciar la operación
(por ejemplo mantenimiento ocupado) siguen teniendo feedback local.

Los resultados detallados, indicadores de progreso, validaciones y errores de
carga con opción de reintentar permanecen en sus vistas.

El menú Eventos muestra un contador de ejecuciones con novedades (hasta `99+`), también plegado.
Inicio y finalización comparten una unidad; una finalización posterior a la lectura
del inicio vuelve a marcar esa ejecución como pendiente.
El último cursor leído se guarda por navegador en `eplsync.events.lastRead` y se
comparte entre sus pestañas. Al abrir Eventos y cargar sus resultados se reconocen
las novedades hasta ese cursor. El contador se consulta al conectar y se refresca
por SSE, sin sondeo periódico; incluye eventos registrados con la web cerrada y
excluye los borrados. Cerrar un aviso flotante no lo marca como leído.


La vista Eventos consulta `/api/events/operations`: agrupa por ejecución, ordena
por inicio descendente y muestra inicio, fin, duración y resultado. El resumen
despliega la secuencia conservada con cifras formateadas y permite copiar detalles.
Las fechas se presentan en la zona horaria del navegador; el backend usa UTC.

Durante una visita se conserva el cursor de la primera consulta para paginar y
filtrar sin incorporar nuevos eventos. El canal SSE actualiza el aviso de novedades
y el contador, pero no recarga la tabla. «Actualizar tabla», salir y volver, o
recargar la página incorporan los datos actuales. El botón de actualización aparece junto al contador de ejecuciones, dentro de la
tabla y sin desplazar filas. La desconexión se comunica mediante un aviso flotante. Las novedades se reconocen al cargar los resultados, no
al recibirlas mientras se consulta una tabla anterior.

Filtros, página, resúmenes abiertos y desplazamiento se conservan en
`sessionStorage` por pestaña (`eplsync.events.navigation`). No se conserva el
cursor entre visitas: volver a Eventos siempre consulta los datos actuales.

Ajustes > General > Notificaciones configura inicios (incluidas reanudaciones y
recuperaciones), finales/cambios de estado, resultados correctos/con avisos/con
errores, y duración de 1 a 60 segundos. Por defecto se muestran todos los resultados
y los inicios. Las preferencias se guardan por navegador en
`eplsync.notificationPreferences`, se aplican también a avisos locales y no
afectan al registro persistente ni al contador. Un aviso ya mostrado conserva su
duración; los cambios se aplican a los siguientes.

Los envíos individuales y por filtros producen eventos `JOB / DOWNLOAD` mediante
su trabajo. El frontend recibe inicio y resultado mediante SSE; los fallos de
transporte siguen siendo avisos locales.

Los filtros del catálogo admiten varios valores por campo: Intro añade un criterio
sin salir del campo; Tabulador también lo añade sin avanzar, y con el campo vacío
avanza al siguiente. Intro con el campo vacío ejecuta Buscar. Retroceso o Suprimir
con el campo vacío eliminan el último criterio. Buscar incorpora los textos
pendientes. Los valores inválidos se conservan para corregirlos y bloquean Buscar.
Cada criterio se elimina con su X, sin separar nombres por comas. Los desplegables
de idioma y estados admiten selección múltiple. Dentro del campo se aplica OR,
entre campos AND. Los filtros rápidos añaden alternativas sin duplicados, la URL
conserva todas al paginar/volver/recargar y las fechas mantienen sus rangos.

### Selección del catálogo y acciones

La columna Selección aparece por defecto al principio y se puede ocultar, mover y
redimensionar. Las preferencias anteriores se conservan y reciben la nueva columna
delante. El checkbox de cabecera actúa solo sobre la página visible; al paginar se
conservan las selecciones, y cambiar filtros las limpia. Ocultar la columna no oculta
la barra de acciones ni borra la selección.

Seleccionar todos representa los filtros actuales y un conjunto de exclusiones:
no descarga todos los libros al navegador. Enviar abre un popup con valores del
servidor y crea un trabajo mediante POST /api/torrent/books. La selección explícita
usa `filters.selectedIds`; la global conserva los filtros y `filters.excludedIds`.
La selección global se evalúa al confirmar, por lo que un cambio concurrente del
catálogo puede afectar al total.

Exportar usa POST /api/catalog/magnets/export con esos mismos filtros: incluye todos
los hashes y elimina duplicados. El botón abre directamente showSaveFilePicker,
si está disponible, con magnets.txt como nombre sugerido y sin imponer extensión.
Se abre antes de solicitar la exportación; si se cancela no se consulta la API.
En los demás navegadores se descarga magnets.txt usando la configuración de
destino del navegador, sin mostrar un formulario intermedio.

### Ordenación múltiple del catálogo

El panel «Ordenar» permite añadir, quitar y mover criterios por prioridad.
Un clic en una cabecera sustituye los criterios; Mayús + clic añade la columna
o invierte su sentido conservando su prioridad. Las flechas y números reflejan
el orden del panel. Las columnas ocultas también pueden seleccionarse en el panel.
La URL conserva los criterios explícitos mediante parámetros sort repetidos.
La petición al backend añade eplId ascendente como desempate si no se eligió EPL ID.
Cambiar la ordenación vuelve a la primera página sin borrar filtros ni selección.

Las notificaciones de eventos enlazan a /events?operationId=<id>. Esta vista
consulta la operación concreta y despliega su resumen, sin aplicar ni sobrescribir
los filtros guardados del listado general. Si la retención o el mantenimiento
han eliminado la operación, se indica que ya no está disponible.

Abrir el detalle de una operación marca solo su última entrada visible como leída,
sin adelantar el cursor global sobre otras operaciones pendientes. Estas marcas
se conservan en localStorage; al visitar el listado general se compactan contra
el cursor global. La consulta POST de no leídos evita URLs excesivamente largas.
