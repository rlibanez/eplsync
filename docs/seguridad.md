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
