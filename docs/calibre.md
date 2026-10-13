# Calibre

[Guías](README.md) · [Inicio](../README.md)

La integración permite consultar bibliotecas mediante el servidor de contenido de
Calibre. Funciona con Calibre nativo en Windows/Linux y con la imagen
`lscr.io/linuxserver/calibre`. No requiere instalar `calibredb` en EPL Sync.

## Configuración

Un administrador configura la conexión en **Ajustes → Calibre**:

1. Activa el servidor de contenido en Calibre. Puedes configurar su inicio
   automático en Preferencias → Compartir por la red.
2. Introduce su URL y, si requiere autenticación, el usuario y la contraseña del
   servidor de contenido.
3. Pulsa **Comprobar conexión**. Se utilizan los datos actuales del formulario,
   sin guardarlos, y se muestran las bibliotecas accesibles.
4. Selecciona la biblioteca y pulsa **Guardar**. Se comprueba nuevamente que
   está disponible antes de guardar la configuración compartida.
5. Abre **Calibre → Biblioteca** y pulsa **Sincronizar biblioteca**.

Ejemplos de URL:

- Calibre nativo: `http://192.168.2.5:8080`.
- LinuxServer en la misma red Docker: `http://calibre:8081`.

En Docker, `localhost` identifica al contenedor de EPL Sync. Para conectar con
Calibre en el host o en otro contenedor, utiliza una dirección accesible desde
EPL Sync. En LinuxServer, 8080/8181 corresponden al escritorio remoto; 8081 es el
puerto habitual del servidor de contenido, que debe estar activo. Sus cuentas son
independientes de la contraseña del escritorio remoto.

La contraseña se cifra en la base de datos utilizando `EPLSYNC_SECRET_KEY`, igual
que las credenciales de qBittorrent. El campo queda vacío después de guardar:
vacío conserva la contraseña existente si no cambia la URL ni el usuario. Puedes
eliminarla explícitamente con su checkbox. Al cambiar de destino o usuario, las
credenciales anteriores no se reutilizan. Si falta la clave de cifrado, el
formulario ofrece el diálogo de generación antes de poder guardar una contraseña.

## Consulta

Los identificadores EPL y las revisiones se leen de los metadatos de Calibre:

```json
{"identifiers": {"epl": "80899", "revision": "1.0"}}
```

No se infieren coincidencias por título. Los libros sin identificador EPL válido
no se vinculan al catálogo.

Las consultas están paginadas (máximo 100 libros) y los metadatos se solicitan en
lotes. Se admiten hasta dos operaciones simultáneas, respuestas de hasta 2 MiB y
un plazo de 10 segundos por petición. No se siguen redirecciones con credenciales:
configura la URL final. Se admite autenticación Basic y Digest.

## Información en la ficha del libro

Pulsa **Sincronizar biblioteca** en Calibre → Biblioteca después de guardar la
conexión. EPL Sync guarda una copia local de los títulos, autores, identificadores EPL y las
revisiones, sin descargar EPUB ni modificar Calibre. Repite la sincronización
cuando cambie tu biblioteca; no se consulta Calibre al navegar por el catálogo.

La ficha del libro muestra las copias vinculadas mediante
`identifiers.epl`, con sus IDs y revisiones de Calibre. Una revisión inválida o
ausente no impide identificar el libro, pero no permite comparar versiones.
La tabla de la ficha muestra las copias con paginación, las mismas columnas que
Biblioteca y los controles **Ordenar** y **Columnas**. Aparece después del
historial de descargas.

Cada fila compara su revisión de Calibre con la del catálogo.
La biblioteca y la fecha de la última sincronización se muestran en
**Calibre → Biblioteca**.
Los datos de la biblioteca requieren el permiso `CALIBRE_READ`;
la URL y las credenciales siguen restringidas al administrador.

Las columnas y los filtros de Calibre en la tabla Catálogo quedan para una fase
posterior.

La sincronización procesa páginas de 100 libros, con un máximo de 500000 libros y
10 minutos. Solo puede ejecutarse una a la vez. Los datos se publican juntos al
completarla: un error conserva la copia anterior y elimina los datos incompletos.
Al cambiar URL, usuario o biblioteca, se descarta la copia anterior y es necesario
sincronizar otra vez. Las importaciones del catálogo no eliminan esa copia.

Descargas → Estado mantiene su funcionamiento. El envío de libros a Calibre y la
sustitución de revisiones quedan para una fase posterior.

## Biblioteca de Calibre

La sección **Calibre > Biblioteca** consulta la copia local sincronizada. Muestra
una fila por registro de Calibre, incluidos los libros con el mismo EPL ID y
revisiones distintas. Incluye EPL ID, libro con portada del catálogo, autor, revisión en Calibre,
revisión del catálogo, comparación y presencia en el catálogo. El ID de Calibre es la primera columna por defecto; no dispone de filtro propio.

Puedes buscar por EPL ID, título o autor y filtrar por comparación y presencia. Las
columnas se pueden ordenar, ocultar, reordenar y redimensionar. La consulta está
paginada, con un máximo de 1000 registros por página.

- **Consultar biblioteca de Calibre (`CALIBRE_READ`)** permite consultar esta
  vista y los datos de Calibre de las fichas de libros.
- **Sincronizar biblioteca de Calibre (`CALIBRE_SYNC`)** permite actualizar la
  copia local e implica el permiso de consulta.

La configuración de conexión continúa siendo exclusiva de administradores.
Abrir una ficha del catálogo requiere además `CATALOG_READ`. Esta funcionalidad
no envía libros ni modifica la biblioteca de Calibre.

Las copias anteriores deben volver a sincronizarse para completar título y autores.

## Abrir libros en Calibre

En la ficha del catálogo, **Ver en Calibre** abre una nueva pestaña. Si hay
varias copias del mismo EPL ID, permite elegir la revisión y su Calibre ID.
El desplegable incluye paginación para consultar todas las copias. El enlace
**Calibre ID** de cada fila abre directamente esa copia, tanto en Biblioteca
como en la ficha individual. Se requiere `CALIBRE_READ`.

En **Ajustes → Calibre**, la **URL para abrir en el navegador** es opcional.
Si está vacía, se utiliza la URL del servidor de contenido. Configúrala cuando
el navegador necesite una dirección distinta de la utilizada por el backend,
por ejemplo una dirección pública en lugar del nombre de un contenedor.
Puede incluir el prefijo de un proxy, como `https://books.example/calibre`.

Esta URL no cambia la conexión del backend ni invalida la biblioteca
sincronizada. Los enlaces no incluyen las credenciales guardadas; Calibre
puede pedir iniciar sesión al abrirlos. Ambas URL admiten HTTP/HTTPS y rechazan
credenciales, parámetros y fragmentos.

## Descargar EPUB

El icono **Descargar EPUB**, con tooltip, está disponible en la columna **Acciones** de cada fila de **Calibre → Biblioteca**
y en el apartado Calibre de la ficha individual. Usa el Calibre ID y la biblioteca
configurada, por lo que permite descargar revisiones diferentes del mismo EPL ID.
No requiere acceso a las carpetas de Calibre ni instalar `calibredb`.
El icono **Ver en Calibre** abre esa copia en una pestaña nueva y solo requiere
`CALIBRE_READ`. La columna Acciones se puede ocultar y reordenar desde **Columnas**;
no se utiliza como criterio de ordenación.

Con la primera columna puedes seleccionar varios libros y pulsar **Descargar**:
un libro se entrega como EPUB y varios como ZIP. Los nombres incluyen el Calibre ID
para distinguir las copias. La selección se limita a la página visible y se limpia
al cambiar filtros, ordenación o página. Si falta el formato EPUB o falla el acceso,
se muestra el error antes de iniciar la descarga; no se entrega un ZIP parcial.

Se requiere **Descargar EPUB de Calibre** (`CALIBRE_FILES_READ`), que implica
`CALIBRE_READ` y no concede sincronización ni descargas del cliente torrent.
Los administradores disponen de este permiso automáticamente. Las credenciales
se utilizan exclusivamente en el backend y no se incluyen en los enlaces.

Los archivos se preparan temporalmente, sin cargarlos completos en memoria, y se
eliminan al terminar la entrega. Los enlaces son de un solo uso, pertenecen a la
sesión que los creó y caducan tras dos minutos; las copias abandonadas se eliminan
en la siguiente comprobación, cada 30 segundos. Un cambio en la conexión invalida
las descargas pendientes.

Límites: 1000 libros por selección, 256 MiB por EPUB y 512 MiB en total; dos
preparaciones o entregas pendientes simultáneas. La preparación tiene un plazo de
cinco minutos, con hasta 60 segundos por archivo. Durante la creación de los ZIP,
el uso temporal de disco puede alcanzar aproximadamente 2 GiB entre ambas
operaciones. Se comprueba que la respuesta sea un EPUB y no se siguen redirecciones.
La descarga utiliza el servidor de contenido; la URL opcional del navegador solo
sirve para abrir las fichas de Calibre.
