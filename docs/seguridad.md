# Usuarios y seguridad

La instalación comparte un catálogo, un cliente qBittorrent y su historial. Toda la API de operaciones requiere sesión y permisos; la información de salud, el estado de inicialización y el inicio de sesión son públicos. No existe una cuenta ni contraseña predeterminada.

## Primer administrador y recuperación

En la primera carga de la aplicación web aparece un asistente para crear la cuenta ADMIN: nombre de usuario, email obligatorio, contraseña y confirmación de contraseña. Deben coincidir las dos contraseñas y cumplir la longitud mínima configurada (8 por defecto), con un máximo de 256 caracteres. La contraseña inicial es definitiva; al crear la cuenta se inicia sesión directamente.

El asistente solo está disponible si no existe ninguna cuenta. El servidor comprueba esa condición dentro de una transacción con bloqueo de escritura de SQLite: dos peticiones simultáneas no pueden crear dos primeros administradores. Desactivar una cuenta no reabre el asistente; un reinicio completo que borre usuarios sí lo reabre. Las peticiones necesitan CSRF y están sujetas al límite de intentos.

Completa el asistente antes de hacer accesible una instalación nueva: la primera persona que lo complete obtiene ADMIN.

Si el administrador olvida su contraseña, utiliza la terminal del contenedor:

```sh
docker compose exec eplsync /app/eplsync-admin reset admin
# También admite el email del administrador:
docker compose exec eplsync /app/eplsync-admin reset admin@example.org
```

El comando muestra una contraseña aleatoria **solo en la terminal**, válida durante cinco minutos. Tanto el inicio de sesión como el cambio obligatorio a una contraseña definitiva deben completarse antes de que caduque. Si caduca, ejecuta el comando otra vez. Reemplaza la contraseña anterior y revoca sus sesiones; no reactiva cuentas deshabilitadas.

La búsqueda por email ignora mayúsculas y minúsculas y solo considera cuentas ADMIN. Si varios administradores comparten email, el comando rechaza la recuperación por email y exige el username. El email es un identificador para esta herramienta local, no una prueba de propiedad del correo.

Con Java directamente, ejecuta `java -jar app.jar --admin reset USER_O_EMAIL` desde el mismo directorio y con la misma configuración de SQLite que el servidor. El acceso a esta terminal constituye acceso administrativo a la instalación. La herramienta no crea el primer administrador.

## Cuentas y permisos

En **Ajustes → Usuarios y seguridad**, el apartado **Usuarios** muestra las cuentas y sus permisos efectivos. Los botones de edición y creación abren formularios; borrar requiere confirmar una acción irreversible y revoca las sesiones de la cuenta. El servidor impide borrar el último administrador activo. El catálogo y el historial compartido se conservan. Debajo está **Configuración de acceso**. ADMIN crea cuentas con nombre, email obligatorio y rol. La contraseña temporal de estas cuentas caduca en 24 horas y debe cambiarse en el primer acceso. ADMIN también puede generar otra contraseña temporal para una cuenta activa. El email queda sin verificar: no se usa para recuperación automática y no tiene que ser único. Cambiarlo elimina cualquier verificación previa.

ADMIN dispone de todos los permisos y administra usuarios, seguridad y reinicio de datos. Estas tres capacidades no pueden concederse a USER. Debe permanecer al menos un administrador activo. USER hereda únicamente `CATALOG_READ`; cada permiso admite heredar, permitir o denegar individualmente.

| Permiso | Acceso |
| --- | --- |
| CATALOG_READ | Catálogo, fichas y magnets |
| BOOK_HISTORY_READ | Historial de descargas de un libro; también en respuestas del catálogo |
| TORRENT_SEND | Enviar, preparar opciones y renombrar torrents |
| TORRENT_SYNC | Consultar y sincronizar descargas, y vincular torrents |
| TORRENT_JOBS_MANAGE | Consultar y administrar trabajos compartidos |
| TORRENT_CLEANUP | Retirar torrents de versiones anteriores |
| TORRENT_FILES_DELETE | Borrar también archivos; requiere TORRENT_CLEANUP |
| CATALOG_IMPORT | Previsualizar e importar el catálogo |
| CATALOG_DELETE | Revisar y borrar libros ausentes |
| COVERS_MANAGE | Comprobaciones y tareas de portadas |
| EVENTS_MANAGE | Consultar y borrar eventos compartidos |
| SETTINGS_MANAGE | Consultar y editar los ajustes del sistema; cambiar el destino y la autenticación de qBittorrent requiere ADMIN |

El registro público está deshabilitado y la aprobación obligatoria está habilitada inicialmente. ADMIN puede cambiar ambos ajustes. El registro abierto solo crea USER; si requiere aprobación, la cuenta queda pendiente y no puede iniciar sesión. Los cambios en estado, rol o permisos revocan las sesiones existentes.

## Credenciales de qBittorrent

Solo ADMIN puede modificar el destino y la autenticación de qBittorrent o restaurar sus ajustes de instalación. `SETTINGS_MANAGE` permite administrar las demás opciones operativas, pero no concede esas capacidades.

Las credenciales quedan vinculadas al protocolo, host, puerto y ruta base del cliente. Se normalizan el uso de mayúsculas en el host, los puertos predeterminados y las barras finales. Cambiar el destino elimina las credenciales anteriores sin modificar el interruptor de integración; volver al destino anterior no las recupera. Si la integración está habilitada, el administrador debe aportar credenciales nuevas completas en el mismo guardado. Si faltan, se rechaza el guardado y se conserva la configuración previa.

Las credenciales de `.env` solo se utilizan para el destino de instalación correspondiente. Un destino personalizado sin credenciales nunca hereda las de instalación. La restauración recupera conjuntamente el destino y las credenciales de instalación. Esta vinculación se guarda en SQLite y se comprueba al arrancar. Las configuraciones antiguas con credenciales persistidas y un destino distinto al de instalación, sin una vinculación conocida, se desactivan y requieren configurar nuevamente las credenciales.

El cliente verifica el destino antes de cada envío y no sigue redirecciones HTTP, ni siquiera al iniciar sesión. Los clientes y las cookies de sesión se sustituyen al cambiar la configuración. Las operaciones ya iniciadas conservan su configuración original y su destino original. Los logs de peticiones no incluyen tokens, contraseñas, cabeceras Authorization ni cookies.

Se permiten direcciones locales, privadas y de redes Docker. La configuración del destino por ADMIN autoriza ese endpoint; no hay una lista externa de hosts permitidos. Las conexiones HTTPS utilizan la verificación de certificados predeterminada de Java, sin desactivar su validación. Los secretos introducidos desde la interfaz se persisten en SQLite; usar un gestor externo de secretos requeriría una integración adicional.

## Descarga del catálogo y redirecciones

`CATALOG_IMPORT` es el único permiso para importar. ADMIN y USER con ese permiso pueden utilizar URLs HTTP o HTTPS, de cualquier dominio y con puertos personalizados, incluidos servidores locales, privados, Docker o WebDAV. No se exige un rol administrativo adicional ni hay una lista de dominios permitidos. Subir un ZIP local mantiene la misma autorización.

El descargador valida la URL en todos los flujos: importación, previsualización y URL inicial de Ajustes o `.env`. Se rechazan protocolos distintos de HTTP/HTTPS, puertos inválidos, credenciales incrustadas en la URL y fragmentos. Los parámetros de consulta, incluidos los tokens de enlaces firmados, se conservan al descargar. En redirecciones se elimina únicamente el fragmento desde un `#` literal, que no forma parte de la petición HTTP; las secuencias `%23` en rutas y parámetros permanecen intactas. Una redirección que solo cambia el fragmento se rechaza como bucle.

Antes de cada petición se resuelve el destino. La conexión utiliza exclusivamente las direcciones obtenidas y comprobadas para ese salto, sin una segunda consulta DNS sin controlar. El hostname original se conserva para Host, SNI y la validación del certificado HTTPS. No se siguen proxies del sistema automáticamente ni se comparten cookies o credenciales entre saltos.

Las redirecciones se procesan manualmente, con un máximo de cinco saltos y detección de bucles. Cada salto vuelve a validar la URL y sus direcciones IPv4 e IPv6. Si la descarga empieza en un destino que resuelve exclusivamente a direcciones públicas, cualquier salto posterior que resuelva a una dirección interna, local o especial se bloquea, incluso si usa el mismo hostname. Se permiten redirecciones entre dominios públicos distintos. Una descarga iniciada expresamente en un servidor interno o local sigue siendo compatible con ese uso.

Los errores no incluyen fragmentos de respuestas remotas, URLs completas ni parámetros de consulta. El transporte HTTP mantiene deshabilitados los logs de cabeceras y contenido, y los logs de la aplicación no muestran las URLs de descarga.

Esta política permite deliberadamente acceder a la red interna mediante `CATALOG_IMPORT`. La protección de DNS y redirecciones evita desviar una descarga pública hacia esa red; no elimina la capacidad de solicitar directamente destinos internos que concede ese permiso.

## Límites y validación de las importaciones

Las descargas remotas, subidas multipart y reutilizaciones de ZIP guardados comparten
los límites definidos en `CatalogImportLimits` del backend. Son límites de seguridad
fijos; no añaden variables de entorno ni ajustes de usuario.

| Recurso | Máximo |
| --- | --- |
| ZIP recibido o guardado | 128 MiB |
| CSV y cualquier otra entrada descomprimida | 512 MiB por entrada |
| Datos descomprimidos de todas las entradas | 768 MiB |
| Entradas del ZIP, incluidos directorios y archivos descartados | 128 |
| Relación de compresión por entrada | 200:1 |
| Descarga completa, incluidos DNS y redirecciones | 5 minutos |
| Extracción y validación estructural del CSV | 2 minutos |
| Registros del CSV | 1.000.000 |
| Columnas del CSV | 64 |
| Caracteres por registro, incluidas sus líneas | 1.048.576 |
| Líneas físicas por registro multilínea | 1.000 |
| Errores de conversión de datos | 1.000 |

El tamaño declarado por HTTP permite un rechazo temprano, pero el límite también se
comprueba contando los bytes realmente recibidos, antes de escribirlos. La descarga
se cancela al agotar su plazo total aunque el servidor continúe enviando datos. No se
aplica descompresión HTTP automática adicional al ZIP.

Antes de abrir el ZIP se valida su directorio central y se cuenta su contenido, para
limitar también la memoria utilizada por el índice del archivo. Solo se admiten ZIP
de un volumen, incluidos ZIP64 con los mismos límites. Se validan también los
registros ZIP64, sus offsets y recuentos, sin permitir desbordamientos ni tamaños
incoherentes. La extracción comprueba los tamaños declarados,
los bytes realmente descomprimidos, el consumo real del descompresor y el CRC de cada entrada, incluidas las que no se
importan. Los nombres del ZIP nunca se utilizan como rutas de extracción ni como comandos.
Se rechazan rutas absolutas, componentes `.` y `..`, nombres con caracteres de
control o de formato invisible y nombres excesivamente largos. Se admiten carpetas
normales dentro del ZIP. Los nombres descriptivos del ZIP y del CSV se normalizan
como Unicode NFC, se limpian de caracteres de control, separadores de línea y
marcas invisibles y se limitan a 200 caracteres. El frontend los presenta como texto,
sin interpretarlos como HTML.

Los rechazos de validación devuelven una explicación breve a la interfaz y al evento
de importación fallida, además del log. Solo se publican mensajes de excepciones
explícitamente seguras; los mensajes técnicos de otras excepciones no se copian a
Eventos. La interfaz conserva las traducciones de los códigos conocidos y muestra
el detalle seguro del backend para el resto de rechazos.

Debe existir exactamente un CSV no vacío, en UTF-8, con las columnas `EPL Id`,
`Título`, `Autor` y `Revisión`, sin cabeceras duplicadas y con el mismo número de
columnas en cada registro. Se siguen admitiendo campos entre comillas, sinopsis
multilínea y la reparación del formato conocido de la columna Portada. Los registros
sobredimensionados se rechazan antes de que OpenCSV los acumule en memoria. Los
errores de conversión aislados siguen contabilizándose; un CSV sin ningún libro válido se rechaza; un exceso aborta la operación
y no se guardan las filas originales en la cola de errores.

Un fallo de descarga, descompresión o validación elimina sus temporales y no modifica
el catálogo ni sus metadatos. Un ZIP malformado no se conserva para reutilizarlo.
Iniciar una nueva fuente sigue invalidando el ZIP guardado anteriormente, incluso si
la nueva carga falla. Un fallo posterior de persistencia mantiene el ZIP ya validado
para reintentar y revierte los cambios de la transacción.

### Política de actualización y reemplazo

La previsualización muestra las filas válidas y los errores de conversión o los IDs
duplicados, sin modificar el catálogo. Una actualización admite esas filas válidas,
conserva los libros anteriores y comunica un resultado parcial. Los fallos de formato,
integridad, límites o persistencia abortan la operación completa.

El reemplazo exige al menos un libro válido y cero errores, incluidos los IDs
repetidos. Antes de borrar, se valida la estructura y se recorren todas las filas del
mismo CSV temporal, sin escribir libros ni consultar el catálogo para clasificarlos.
Si hay errores, se comunica su número y se conserva el catálogo anterior. La
importación definitiva vuelve a comprobar el resultado dentro de la transacción:
cualquier fallo revierte el borrado y las inserciones anteriores, junto con los
metadatos. Un CSV vacío, con solo cabecera o sin libros válidos tampoco puede vaciar
el catálogo. El borrado intencionado de datos pertenece a la operación de reinicio.

## Contraseñas y sesiones

Las contraseñas se guardan con Argon2id (19 MiB, dos iteraciones, paralelismo uno), sal aleatoria de 16 bytes y hash de 32 bytes. La longitud mínima es configurable en **Ajustes → Usuarios y seguridad**, entre 8 y 128 caracteres (8 por defecto); el máximo es 256. Antes de inicializar la instalación, `EPLSYNC_SECURITY_PASSWORD_MIN_LENGTH` fija el mínimo inicial. La configuración se persiste y los cambios afectan solo a nuevas contraseñas. Las contraseñas temporales tienen al menos 192 bits aleatorios y también se almacenan como hash. La API nunca devuelve hashes; solo devuelve la contraseña temporal al generarla.

Las sesiones usan una cookie HttpOnly y SameSite=Lax, con 30 minutos de inactividad y duración máxima de 12 horas, configurables por ADMIN. Las credenciales temporales y sus sesiones caducan conjuntamente. Cambiar o restablecer la contraseña revoca todas las sesiones. El servidor valida el estado y la versión de seguridad de la cuenta en cada petición. Los eventos SSE verifican la sesión en sus latidos y cierran las conexiones revocadas.

Las operaciones que modifican datos requieren CSRF, incluido login e importaciones multipart. React solicita el token y lo envía en la cabecera indicada por el servidor. Los intentos de login, registro y cambio de contraseña están limitados en memoria por intervalos de cinco minutos; el límite se reinicia al arrancar la aplicación.

## Despliegue

Docker publica el puerto en `127.0.0.1` por defecto. Con `EPLSYNC_SECURITY_REQUIRE_HTTPS=false`, la aplicación admite HTTP y HTTPS, sin restringirlos según la IP de origen. Para exigir conexiones cifradas, sirve la aplicación mediante HTTPS y activa `EPLSYNC_SECURITY_REQUIRE_HTTPS=true` mediante el Compose completo o la configuración externa de la aplicación: la cookie será Secure y el servidor rechazará peticiones que no identifique como HTTPS.

Si un proxy termina TLS, configura `server.forward-headers-strategy: native` y `server.tomcat.remoteip.internal-proxies` según la red de instalación. Ambas opciones están documentadas en `application.yaml`. También pueden sobrescribirse mediante las variables estándar `SERVER_FORWARD_HEADERS_STRATEGY` y `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`; en Docker hay que pasarlas explícitamente al contenedor mediante una configuración adicional de Compose. Añadirlas únicamente a `.env` no las transmite al contenedor. El backend debe ser accesible únicamente desde ese proxy; no confíes en cabeceras reenviadas desde clientes arbitrarios. Ajusta `HOST_BIND` a la interfaz que utiliza el proxy. En ejecución directa sin Docker, configura `SERVER_ADDRESS=127.0.0.1` para el uso local.

## Reinicio de datos

El reinicio normal conserva cuentas y configuración. El checkbox **Borrar también usuarios y toda la configuración** exige escribir `BORRAR TODO`: elimina también esos datos, cierra las sesiones y vuelve a mostrar el asistente web para crear el primer administrador. No elimina archivos de qBittorrent.

## API y nuevas operaciones

Para scripts, conserva las cookies al solicitar `GET /api/auth/csrf`, envía su `token` en la cabecera `headerName` al hacer `POST /api/auth/login` y solicita un token nuevo después del login. Conserva las cookies en las llamadas posteriores. Las respuestas protegidas no se almacenan en cachés HTTP. Una sesión ausente o caducada devuelve 401; permisos o CSRF insuficientes, 403.

Los controladores de `/api` necesitan una política explícita `@PreAuthorize`, de clase o método. Un endpoint nuevo sin política se deniega por defecto. Las pruebas de seguridad ejercitan peticiones reales a la cadena de Spring Security; las pruebas funcionales anteriores utilizan una identidad con permisos completos.


Las contraseñas temporales generadas desde **Usuarios → Acciones** se muestran en una ventana emergente una sola vez, sin guardarse en el navegador. También se permite reiniciar la del administrador conectado: su sesión se cierra, pero la ventana permanece visible hasta que se oculta. El editor de permisos no incluye otra acción de recuperación.

La creación, edición, borrado y recuperación de cuentas genera eventos en la categoría **Seguridad**, vinculados a la transacción que realiza el cambio. Contienen identificadores y el nombre de usuario, nunca contraseñas, hashes ni emails. Solo los administradores pueden verlos en el historial, los contadores de no leídos y las notificaciones SSE; disponer de `EVENTS_MANAGE` no permite ver eventos de seguridad. Se aplica la retención habitual de eventos.

### Límites de consultas de listados

Libros y magnets siempre devuelven `items` y `meta`, con página inicial 0 y tamaño
predeterminado 20. Los listados de catálogo, descargas, trabajos y sus elementos
rechazan tamaños fuera de 1–1000 y desplazamientos mayores que 2147483647.
Eventos y ausentes conservan sus máximos de 200 y 100; el directorio admite sus
opciones existentes hasta 1000. Catálogo y descargas aceptan como máximo ocho
criterios de ordenación, sobre campos autorizados. Las selecciones/exclusiones
explícitas admiten 10000 IDs por campo, incluyendo los cuerpos JSON.

Los libros, descargas y trabajos se paginan en SQLite. En el directorio alfabético,
SQLite filtra, deduplica y ordena antes de aplicar el límite, conservando la
normalización Unicode y la ordenación española. Para magnets se recorre una
proyección mediante un cursor y se deduplica en una tabla temporal de SQLite;
solo la página se materializa en Java. La tabla se elimina también ante fallos.
Este recorrido sigue necesitando examinar los libros filtrados y ocupar la
conexión durante la consulta. Las consultas y previsualizaciones costosas admiten como máximo cuatro solicitudes
simultáneas por instalación. El exceso se rechaza con HTTP 429 y `Retry-After`,
sin una cola adicional de trabajo.

### Exportaciones de magnets

GET y POST `/api/catalog/magnets/export` generan un archivo temporal UTF-8 antes
de comenzar la respuesta. La lectura usa proyecciones de hasta 250 libros por
transacción, liberando la conexión del catálogo entre lotes y antes de descargar.
Un índice SQLite independiente en disco elimina hashes repetidos conservando
el primer libro según la ordenación solicitada. Un cambio de versión del catálogo
importado durante la preparación cancela el resultado con HTTP 409.

Límites por instalación: una exportación activa (incluida su transferencia),
128 MiB de texto, 128 MiB de índice y dos minutos para preparar el archivo.
Los límites de tamaño producen HTTP 413, el tiempo de preparación HTTP 408 y una
segunda exportación HTTP 429. Se rechazan las solicitudes excedentes sin encolarlas.
Los fallos de almacenamiento muestran un mensaje claro; nunca se envía un archivo
parcial si la preparación falla.

La transferencia usa bloques de 8 KiB, un único hilo y ninguna cola de tareas,
con un tiempo máximo asíncrono de dos minutos. Éxito, desconexión y cancelación
cierran y eliminan los temporales. Si una escritura de red sigue bloqueada después
de cancelar, mantiene el turno de exportación hasta salir; así no se acumulan hilos.
Una respuesta ya iniciada se interrumpe ante un fallo, sin añadir JSON al archivo.
Los temporales se limpian durante el funcionamiento normal; una terminación abrupta
del proceso puede dejar archivos para la limpieza del directorio temporal del sistema.

La interfaz muestra el detalle de rechazo del servidor. Cuando el navegador ofrece
la API de escritura de archivos, guarda los bloques progresivamente con control de
flujo; en otros navegadores utiliza un Blob, acotado por el máximo de 128 MiB del servidor.

## Comprobación de portadas y destinos de red

Las portadas admiten HTTP y HTTPS hacia direcciones públicas. La clasificación de
IPv4 e IPv6 se comparte con la importación y excluye redes internas y rangos
especiales, incluido `100.64.0.0/10`. Se rechazan respuestas DNS con alguna
dirección no pública. Cada conexión utiliza exclusivamente las IP validadas,
conservando el dominio de la URL para Host, SNI y la verificación del certificado.
Cada redirección se valida de nuevo, con un máximo de tres saltos; DNS y las
peticiones comparten el tiempo máximo configurado. No se descarga el cuerpo de
la imagen ni se siguen redirecciones automáticamente. Un rechazo deja el estado
de disponibilidad indeterminado, sin marcar la portada como ausente.

La importación conserva su política independiente: permite destinos locales
introducidos directamente, como un WebDAV de la red doméstica.

## Credenciales almacenadas y clave de cifrado

Las contraseñas y API keys de qBittorrent guardadas desde Ajustes se cifran con
AES-256-GCM antes de escribirse en SQLite. Cada escritura usa un nonce aleatorio;
la autenticación incluye el nombre del ajuste. Las contraseñas de usuarios
continúan almacenándose mediante hash, sin cifrado reversible.

Configura `EPLSYNC_SECRET_KEY` en `.env` antes de guardar contraseñas o API keys
mediante la interfaz. Genera una clave única para esta instalación con:

```sh
openssl rand -base64 32
```

Copia el resultado en `.env` como `EPLSYNC_SECRET_KEY=RESULTADO` y recrea el
contenedor. Ambos Compose transmiten la variable. No existe una clave
predeterminada, no se genera automáticamente y no se usa un archivo de clave ni
un montaje de secretos. Para ejecutar fuera de Docker, exporta la variable antes
de iniciar Java.

El formato es rígido: Base64 estándar canónico de exactamente 32 bytes (44
caracteres, incluido el `=` final), sin espacios ni saltos de línea. No se aceptan
contraseñas como `mi_password_secreto`, Base64 URL-safe, otros tamaños ni valores
sin el padding final. El formato puede comprobarse; la aleatoriedad de una clave
aportada no puede garantizarse, por lo que debe utilizarse el comando anterior.

La clave no es obligatoria para iniciar la aplicación ni consultar el catálogo.
Si falta y aún no hay secretos cifrados, se permiten los demás ajustes, pero se
rechaza guardar contraseñas/API keys con un aviso claro y sin cambios parciales.
La interfaz abre un popup con **Generar**, **Copiar** y **Cerrar**. Generar utiliza
el generador criptográfico del navegador para crear 32 bytes y mostrarlos en
Base64; Copiar incluye `EPLSYNC_SECRET_KEY=` para pegar la línea en `.env`. La
clave no se envía al servidor ni se activa automáticamente: configura la variable,
recrea el contenedor y vuelve a guardar. El valor se descarta al cerrar el popup.
Si ya hay credenciales cifradas cuya clave falta o es incorrecta, no se ofrece
generar una sustituta: debes recuperar la clave original.
Si la clave está mal formada, o hay credenciales cifradas que no puede descifrar,
se bloquea la integración y se conservan los valores guardados. El resto de la
aplicación sigue disponible; Ajustes y el log muestran el problema sin secretos.
Restablece la clave correcta y recrea el contenedor. No cambies la clave de una
base de datos con credenciales cifradas: no se implementa rotación de claves.

Conserva la clave por separado del backup de SQLite y protege `.env` con permisos
600. Perder la clave hace irrecuperables las credenciales cifradas. Quien obtenga
solamente SQLite no podrá descifrarlas; quien obtenga también `.env`, inspeccione
las variables del contenedor o controle el proceso sí podrá hacerlo. No se
implementa backup/restore. Las credenciales suministradas mediante variables de
entorno no se copian automáticamente a SQLite.

Las escrituras activan `secure_delete` de SQLite. Los backups, snapshots del
disco y copias anteriores del journal/WAL pueden conservar secretos previos en
claro: el cifrado no elimina esas copias. El contenedor crea archivos con
`umask 077`; no cambia los permisos de archivos existentes en el host. Restringe
los directorios de datos a 700 y SQLite, WAL, journals y backups a 600, con el
propietario correspondiente a PUID/PGID. Fuera de Docker usa también `umask 077`.

## Errores públicos y referencias de incidencia

Los errores inesperados devuelven un mensaje genérico sin SQL, rutas internas,
mensajes de librerías ni causas técnicas. La respuesta incluye `incidentId`,
el encabezado `X-Incident-ID` y la referencia dentro de `details`, visible en los
avisos del frontend. El log conserva la excepción técnica con ese mismo UUID;
no se incluye el cuerpo de la petición ni su query string en la respuesta.

Las validaciones deliberadas utilizan `UserInputException` y mantienen sus
mensajes claros. Una `IllegalArgumentException` procedente de una librería recibe
un mensaje genérico, aunque su mensaje original parezca útil. Los rechazos
controlados de ZIP/CSV, descarga y autenticación conservan las explicaciones
preparadas por la aplicación; los errores internos de importación quedan ocultos
con una referencia. Eventos y trabajos tampoco publican mensajes arbitrarios de
excepciones internas. Los logs son información técnica restringida: no los
expongas públicamente.

### Clave opcional de configuración inicial

Para impedir que alguien cree el primer administrador antes que el responsable
de la instalación, configura `EPLSYNC_INITIAL_ADMIN_KEY` antes del primer arranque.
Puedes generar un valor aleatorio de 32 caracteres (128 bits) con:

```bash
openssl rand -hex 16
```

El asistente pide esta clave además de la contraseña elegida para la cuenta.
El backend la exige y limita los intentos, pero nunca devuelve su valor al
navegador ni lo guarda en SQLite. Una variable ausente, vacía o formada solo
por espacios desactiva esta protección. Se admite cualquier otro valor sin
un formato obligatorio. Tras crear la primera cuenta, la clave deja de tener
efecto y el asistente permanece cerrado.

Con Docker, usa `docker-compose-full.yml` para transmitir la variable desde
`.env`, o añádela expresamente al entorno del servicio en tu configuración.

### Preferencias personales de Home

Cada usuario puede ordenar y activar secciones en **Ajustes → Pantalla inicial**
sin permisos de administración. Los límites de 1 a 100 libros se validan en el
backend. La API obtiene siempre la identidad de la sesión, sin permitir consultar
o modificar preferencias ajenas. La configuración permanece en SQLite y los
permisos existentes siguen limitando las consultas de catálogo, descargas y
eventos. Cerrar el encabezado con su X desactiva esa sección para la cuenta; se
puede recuperar desde la misma pestaña de Ajustes.

`TORRENT_SYNC` unifica la consulta del historial global, sincronización y vinculación de descargas.
Los envíos y borrados mantienen sus permisos específicos.
