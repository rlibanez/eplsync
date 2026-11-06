# Usuarios y seguridad

[Guías](README.md) · [Inicio](../README.md)

La instalación comparte un catálogo, un cliente qBittorrent y su historial.
No hay una cuenta ni contraseña predeterminadas.

## Primer administrador

En el primer acceso, crea la cuenta de administrador indicando nombre de usuario,
contraseña y confirmación. No se solicita correo electrónico. El asistente se cierra
después de crear la primera cuenta.

Completa este paso antes de hacer accesible la instalación a otras personas.
Para proteger también el asistente inicial, configura `EPLSYNC_INITIAL_ADMIN_KEY` en `.env` antes de arrancar. Puedes generar
su valor con:

```sh
openssl rand -hex 16
```

El asistente pedirá esa clave además de la contraseña elegida para la cuenta.
Una clave vacía desactiva esta protección; después de crear el primer usuario,
deja de tener efecto.

## Usuarios y permisos

En **Ajustes → Usuarios y seguridad**, un administrador puede crear, editar,
aprobar y eliminar usuarios, y restablecer sus contraseñas. Los roles son:

- **ADMIN:** acceso completo y administración de usuarios y seguridad.
- **USER:** acceso al catálogo por defecto, ampliable mediante permisos individuales.

Los permisos de un usuario normal permiten controlar:

| Permiso | Acceso |
| --- | --- |
| `CATALOG_READ` | Catálogo, Directorio, fichas y enlaces magnet. |
| `BOOK_HISTORY_READ` | Historial asociado a los libros. |
| `TORRENT_SEND` | Enviar libros y actualizaciones a qBittorrent. |
| `TORRENT_SYNC` | Consultar el estado de descargas, sincronizar y buscar actualizaciones. |
| `TORRENT_JOBS_MANAGE` | Consultar y controlar trabajos de envío. |
| `TORRENT_CLEANUP` | Eliminar torrents del cliente. |
| `TORRENT_FILES_DELETE` | Eliminar también sus archivos; requiere permiso de limpieza. |
| `CALIBRE_READ` | Consultar la biblioteca de Calibre. |
| `CALIBRE_SYNC` | Sincronizar su copia local; implica consulta. |
| `CALIBRE_FILES_READ` | Descargar EPUB de Calibre; implica consulta. |
| `CALIBRE_SEND` | Enviar EPUB a Calibre; implica consulta y requiere además `DOWNLOADS_FILES_READ`. |
| `CALIBRE_JOBS_MANAGE` | Consultar y controlar los trabajos de Calibre; implica consulta. |
| `CALIBRE_DELETE` | Eliminar revisiones anteriores de Calibre después de verificar la nueva; implica consulta. |
| `DOWNLOADS_DELETE` | Eliminar registros del historial de EPL Sync. |
| `CATALOG_IMPORT` | Previsualizar e importar el catálogo. |
| `CATALOG_DELETE` | Revisar y eliminar libros ausentes del catálogo importado. |
| `COVERS_MANAGE` | Comprobar y reparar portadas. |
| `EVENTS_MANAGE` | Consultar y borrar eventos compartidos. |
| `SETTINGS_MANAGE` | Administrar ajustes del sistema. |

La administración de usuarios, seguridad, reinicio completo y conexión y
credenciales de qBittorrent permanece reservada a ADMIN. Debe quedar al menos
un administrador activo. La edición de un usuario permite restaurar los permisos
predeterminados de su rol.

Al crear una cuenta desde Ajustes se muestra una contraseña temporal, válida
24 horas, que debe sustituirse en el primer acceso. El correo es obligatorio,
pero no está verificado y no permite recuperar contraseñas mediante email.

El registro público no solicita correo electrónico; los correos de cuentas existentes
se conservan y pueden gestionarse desde el perfil.

El registro público está desactivado por defecto. Si lo habilitas, puedes exigir
aprobación administrativa, activada por defecto. El usuario registrado elige su
contraseña; si requiere aprobación, no podrá iniciar sesión hasta ser aprobado.

Desactivar o borrar una cuenta, cambiar su contraseña o modificar sus privilegios
invalida sus sesiones. Los eventos de administración de usuarios no se muestran
a los usuarios normales.

## Recuperar la contraseña de un administrador

Desde la carpeta de la instalación:

```sh
docker compose exec eplsync /app/eplsync-admin reset NOMBRE_DE_USUARIO
```

También puedes indicar el correo del administrador:

```sh
docker compose exec eplsync /app/eplsync-admin reset admin@example.org
```

El comando muestra una contraseña temporal válida durante **cinco minutos**.
Inicia sesión y establece una contraseña definitiva antes de que caduque. Si
caduca, ejecuta el comando otra vez. La operación sustituye la contraseña anterior
y cierra sus sesiones; no reactiva una cuenta deshabilitada.

Si varios administradores comparten el correo, utiliza el nombre de usuario.
Un administrador puede restablecer las contraseñas de otras cuentas desde la tabla
de usuarios.

## Acceso remoto

El Compose publica el puerto en todas las interfaces del servidor. Para limitarlo
al propio servidor, utiliza `127.0.0.1:8088:8088` en `ports`. Si accedes únicamente
mediante un proxy en la misma red Docker, puedes retirar la publicación de `ports`.

Para publicar la aplicación mediante un dominio, utiliza un proxy inverso con
HTTPS. El Compose permite activar `EPLSYNC_SECURITY_REQUIRE_HTTPS=true`:
rechaza las conexiones identificadas como HTTP y protege la cookie de sesión.
Esta opción no configura certificados ni el proxy.

Si el proxy termina HTTPS y conecta a EPL Sync mediante HTTP, configura estas
variables en `.env`. Compose ya las transmite al contenedor:

```dotenv
EPLSYNC_SECURITY_REQUIRE_HTTPS=true
SERVER_FORWARD_HEADERS_STRATEGY=native
SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=192.168.2.2/32
```

El ejemplo supone que el proxy se conecta desde `192.168.2.2`; `/32` limita la
confianza a esa única dirección IPv4. Sustitúyela por la IP real vista desde EPL
Sync y utiliza una dirección estable. Con los valores predeterminados se ignoran
las cabeceras reenviadas; al activar `native`, el Compose limita por defecto la
confianza a `127.0.0.1/32` hasta que configures el proxy.

La lista admite redes CIDR separadas por comas:

```dotenv
# Una única IP IPv4:
SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=192.168.2.2/32
# Una subred, solo si todos sus miembros deben ser proxies de confianza:
SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=192.168.2.0/24
# Varios proxies concretos (incluido un ejemplo IPv6):
SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=192.168.2.2/32,192.168.2.4/32,::1/128
```

No se utiliza el formato de máscara decimal `255.255.255.0`: su equivalente CIDR
es `/24`. Evita listas vacías, `.*` o redes como `0.0.0.0/0` y `::/0`, que amplían
la confianza a cualquier origen de su familia de direcciones.

También admite una expresión regular Java, por compatibilidad. En ese formato,
`[.]` significa punto literal y `.` significa cualquier carácter; por eso se usa
`192[.]168[.]2[.]2` y no una IP con puntos sin escapar. Para nuevas configuraciones,
utiliza CIDR y evita la ambigüedad.

Valores de `SERVER_FORWARD_HEADERS_STRATEGY`:

| Valor | Comportamiento |
| --- | --- |
| `none` | Ignora las cabeceras reenviadas. Es el valor predeterminado. |
| `native` | Tomcat interpreta las cabeceras del proxy, incluida `X-Forwarded-Proto`, y aplica la lista `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`. Recomendado para este despliegue. |
| `framework` | Spring interpreta las cabeceras reenviadas. No utiliza la lista de proxies de Tomcat: requiere restringir el acceso al proxy y que este elimine o sustituya las cabeceras reenviadas que envíen los clientes. |

El ejemplo anterior utiliza `native`. No es necesario usar `framework` para
reconocer HTTPS terminado en un proxy inverso.

Referencias: [Tomcat RemoteIpValve](https://tomcat.apache.org/tomcat-11.0-doc/api/org/apache/catalina/valves/RemoteIpValve.html) y
[estrategias de Spring Boot](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/web/server/autoconfigure/ServerProperties.ForwardHeadersStrategy.html).

Recrea el servicio con `docker compose up -d`; no necesitas reconstruir la imagen.
Si conservas un Compose antiguo, incorpora también las dos variables `SERVER_…`
del Compose actualizado: añadirlas únicamente a `.env` no las pasa al contenedor.

El proxy inverso puede seguir conectando por HTTP, por ejemplo a
`http://192.168.2.3:8088` si está en otro servidor. Si ambos contenedores comparten
una red Docker, puede utilizar `http://eplsync:8088` y se puede retirar la
publicación de `ports`. Restringe el acceso al puerto de EPL Sync al proxy.

## Credenciales de qBittorrent

Para guardarlas desde Ajustes, configura `EPLSYNC_SECRET_KEY` en `.env` con una
clave generada mediante:

```sh
openssl rand -base64 32
```

Copia únicamente el valor generado. Se requieren exactamente 32 bytes en Base64
estándar: 44 caracteres con su `=` final. Una contraseña elegida manualmente no
es un sustituto válido. Aplica el cambio con `docker compose up -d`.

Si falta la clave, la aplicación permite generar y copiar una desde el formulario,
pero debes configurarla en el despliegue antes de guardar las credenciales.
Conserva la clave y protege `.env`; perderla o cambiarla impide recuperar los
secretos cifrados. Las credenciales suministradas por entorno no se copian
automáticamente al almacenamiento de EPL Sync.

Al cambiar la URL del cliente, introduce las credenciales correspondientes al
nuevo destino. Las anteriores no se reutilizan para otro servidor.
HTTP transmite credenciales sin cifrado: úsalo en un entorno aislado y de confianza,
o utiliza HTTPS para esa conexión.

## Contraseñas, sesiones y diagnóstico

La longitud mínima inicial de contraseña es de ocho caracteres. Un administrador
puede cambiarla en **Configuración de acceso**; el máximo admitido es 256.
En ese mismo apartado se configuran la caducidad por inactividad y la duración
máxima de sesión.

Los inicios de sesión, intentos rechazados, cierres explícitos e invalidaciones
se registran en `eplsync.log`, sin contraseñas ni tokens. Si un error muestra una
referencia de incidencia, úsala para localizar el detalle en los
[logs](logs.md).

## Apariencia personal

El tema claro u oscuro y la paleta de colores se guardan por usuario en la base
de datos. Se cargan al iniciar sesión desde cualquier navegador y sus cambios se
guardan automáticamente, sin exigir permisos de administración de ajustes.
Cada usuario solo puede leer y modificar su propia apariencia.

El navegador conserva una copia local para mostrarla mientras carga la sesión y
en la pantalla de acceso. En el primer inicio, si la cuenta aún no tiene una
apariencia guardada, se adopta esa copia local. Una preferencia ya guardada en la
cuenta tiene prioridad. Si falla el guardado, se conserva el cambio local y se
muestra un aviso en Ajustes; cambiar de nuevo la apariencia reintenta el guardado.
