# Logs y diagnóstico

[Documentación](README.md) · [Inicio](../README.md)

Las rutas y los comandos parten de la raíz del repositorio salvo que se indique otro directorio.

## Logs persistentes

Los logs se escriben en consola y en `./logs/eplsync.log`, relativo al directorio
desde el que arranca Java. En Docker, el Compose incluido monta `./logs` del host
en `/app/logs`; el usuario del contenedor debe poder escribir en ese directorio.
Si arrancas desde `backend`, la ruta local será `backend/logs/eplsync.log`.

El archivo rota al alcanzar 50 MB y al cambiar de día (en la siguiente escritura).
Los históricos se llaman `eplsync.20260928.0.log`, `eplsync.20260928.1.log`, etc.
La fecha corresponde al período del log según la zona horaria de la JVM y el
contador comienza en cero cada día. El umbral no es un límite estricto: una entrada
puede hacer que el archivo supere ligeramente los 50 MB.

Se conservan hasta 30 días de históricos, con un límite adicional de 1 GB para
los archivos rotados; el archivo activo no cuenta para ese límite. La limpieza
se realiza al arrancar y durante la rotación, de forma asíncrona. Los históricos
se guardan sin compresión y están excluidos de Git.

La configuración está en `backend/src/main/resources/application.yaml`. Puedes
ajustar la retención mediante `LOGGING_LOGBACK_ROLLINGPOLICY_MAXHISTORY` y
`LOGGING_LOGBACK_ROLLINGPOLICY_TOTALSIZECAP`. Si cambias la ubicación, ajusta tanto
`LOGGING_FILE_NAME` como `LOGGING_LOGBACK_ROLLINGPOLICY_FILENAMEPATTERN`.

## Log de acceso HTTP

Tomcat registra las peticiones HTTP entrantes en `./logs/access.log`, junto
al log de aplicación. Incluye las rutas `/api`, `/actuator` y las peticiones que
terminan en error. Cada línea contiene fecha y hora con zona horaria, IP remota,
método, ruta con parámetros de consulta, código HTTP, duración en milisegundos,
tamaño de respuesta en bytes (sin cabeceras) y `User-Agent`:

```text
2026-09-29 12:34:56.789 +02:00 192.168.1.20 "GET /api/catalog/books?language=es&page=0&size=100&sort=eplId,asc" 200 42ms 18432B "curl/8.10.1"
```

La duración es el tiempo que Tomcat dedica a atender la petición; en operaciones
que lanzan jobs no incluye la ejecución posterior del trabajo. Los parámetros de
consulta se registran completos: no deben contener contraseñas ni tokens. No se
registran cuerpos ni cabeceras salvo `User-Agent`; si falta, aparece `-`.
Tomcat también representa la ausencia de query string con `-`, por lo que una
petición sin parámetros aparece, por ejemplo, como `"GET /actuator/health-"`.
La IP es la del cliente conectado;
si accede mediante un proxy, puede ser la del proxy. No se confía automáticamente
en cabeceras `X-Forwarded-For` enviadas por el cliente.

Este fichero usa la rotación nativa de Tomcat, independiente de Logback: rota
diariamente a `access.20260929.log` y conserva 30 días. No tiene el límite
de 50 MB ni el tope de 1 GB del log de aplicación. Las líneas se escriben sin
buffer para facilitar el seguimiento con `tail -f`.

Se configura en `server.tomcat.accesslog`; puedes desactivarlo con
`SERVER_TOMCAT_ACCESSLOG_ENABLED=false` o cambiar su directorio con
`SERVER_TOMCAT_ACCESSLOG_DIRECTORY` (usa una ruta absoluta, pues Tomcat resuelve
las rutas relativas contra su propio directorio base).

