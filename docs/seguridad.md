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
| DOWNLOADS_READ | Vista global de descargas |
| TORRENT_SEND | Enviar, preparar opciones y renombrar torrents |
| TORRENT_SYNC | Sincronizar y asociar descargas |
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

Las credenciales quedan vinculadas al protocolo, host, puerto y ruta base del cliente. Se normalizan el uso de mayúsculas en el host, los puertos predeterminados y las barras finales. Cambiar el destino elimina las credenciales anteriores y desactiva la integración; volver al destino anterior no las recupera. Un administrador puede introducir credenciales nuevas completas y activar la integración en el mismo guardado.

Las credenciales de `.env` solo se utilizan para el destino de instalación correspondiente. Un destino personalizado sin credenciales nunca hereda las de instalación. La restauración recupera conjuntamente el destino y las credenciales de instalación. Esta vinculación se guarda en SQLite y se comprueba al arrancar. Las configuraciones antiguas con credenciales persistidas y un destino distinto al de instalación, sin una vinculación conocida, se desactivan y requieren configurar nuevamente las credenciales.

El cliente verifica el destino antes de cada envío y no sigue redirecciones HTTP, ni siquiera al iniciar sesión. Los clientes y las cookies de sesión se sustituyen al cambiar la configuración. Las operaciones ya iniciadas conservan su configuración original y su destino original. Los logs de peticiones no incluyen tokens, contraseñas, cabeceras Authorization ni cookies.

Se permiten direcciones locales, privadas y de redes Docker. La configuración del destino por ADMIN autoriza ese endpoint; no hay una lista externa de hosts permitidos. Las conexiones HTTPS utilizan la verificación de certificados predeterminada de Java, sin desactivar su validación. Los secretos introducidos desde la interfaz se persisten en SQLite; usar un gestor externo de secretos requeriría una integración adicional.

## Descarga del catálogo y redirecciones

`CATALOG_IMPORT` es el único permiso para importar. ADMIN y USER con ese permiso pueden utilizar URLs HTTP o HTTPS, de cualquier dominio y con puertos personalizados, incluidos servidores locales, privados, Docker o WebDAV. No se exige un rol administrativo adicional ni hay una lista de dominios permitidos. Subir un ZIP local mantiene la misma autorización.

El descargador valida la URL en todos los flujos: importación, previsualización y URL inicial de Ajustes o `.env`. Se rechazan protocolos distintos de HTTP/HTTPS, puertos inválidos, credenciales incrustadas en la URL y fragmentos. Los parámetros de consulta, incluidos los tokens de enlaces firmados, se conservan al descargar.

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
