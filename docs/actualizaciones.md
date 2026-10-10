# Actualizaciones de libros

[Guías](README.md) · [Inicio](../README.md)

## Buscar revisiones nuevas

Después de actualizar el catálogo, abre **Descargas → Actualizaciones** y pulsa
**Buscar actualizaciones**. Revisa los estados que quieres comprobar y confirma
la búsqueda. Los valores predeterminados se configuran en
**Ajustes → Descargas → Configuración de actualizaciones de revisión**.

La búsqueda sincroniza primero con el cliente torrent y compara después las
revisiones registradas con las del catálogo. No exige que la revisión anterior
esté descargada: depende de los estados seleccionados. Las revisiones iguales,
inferiores o que ya tienen un envío pendiente no se presentan como otra actualización.

La tabla permite filtrar por estado registrado y seleccionar las actualizaciones
que quieras enviar. Para consultarla necesitas los permisos de catálogo y
sincronización; para enviarlas necesitas además permiso de envío.

## Versiones anteriores

Al enviar actualizaciones, el apartado **Versiones anteriores** permite elegir
la acción:

- Conservar torrents y archivos.
- Eliminar torrents y conservar archivos.
- Eliminar torrents y archivos.

Si eliges eliminar, configura el **Momento de borrado**:

- **Inmediatamente:** intenta eliminar las revisiones anteriores sin esperar a
  que termine la nueva descarga.
- **Cuando la nueva revisión esté descargada:** conserva las anteriores hasta
  confirmar la finalización de los hashes nuevos. Es el comportamiento predeterminado.

El borrado requiere sus permisos y confirmaciones. Las comprobaciones de identidad,
destino y archivos compartidos pueden impedirlo. Los resultados quedan reflejados
en los trabajos y eventos correspondientes.

## Limpiezas pendientes

Cada registro antiguo tiene su propia solicitud de limpieza. Las solicitudes
aceptadas conservan su autorización aunque después cambie o se elimine el usuario
que las creó.

Por defecto se revisan hasta 100 solicitudes por ciclo, con 60 segundos entre ciclos.
Puedes configurar el intervalo, tamaño de lote y activación mediante las
[variables de instalación](variables-entorno.md). La sincronización manual también
procesa las limpiezas que correspondan.

Un borrado cuya respuesta no permite confirmar el resultado no se repite
ciegamente: permanece pendiente de comprobación. Los errores y bloqueos se
registran para poder revisar su motivo.

También puedes gestionar registros concretos desde el
[Historial asociado](descargas.md#historial-asociado-a-un-libro), sin esperar a
que se complete otra revisión.
