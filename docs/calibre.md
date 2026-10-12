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
5. Pulsa **Consultar biblioteca** para leer los libros con los datos guardados.

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

La tabla muestra el ID de Calibre, título, autores, EPL ID, revisión e identificación
EPL. Los identificadores se leen de los metadatos de Calibre:

```json
{"identifiers": {"epl": "80899", "revision": "1.0"}}
```

Los libros sin ambos identificadores válidos aparecen como **Sin vincular**. No se
infieren coincidencias por título. La identificación solo indica que los metadatos
EPL son válidos; no confirma que el libro exista en el catálogo de EPL Sync.

Las consultas están paginadas (máximo 100 libros) y los metadatos se solicitan en
lotes. Se admiten hasta dos operaciones simultáneas, respuestas de hasta 2 MiB y
un plazo de 10 segundos por petición. No se siguen redirecciones con credenciales:
configura la URL final. Se admite autenticación Basic y Digest.

Esta primera fase no modifica Calibre, no descarga EPUB y no persiste sus libros
en EPL Sync. El envío de libros y la sustitución de revisiones quedan para una
fase posterior.
