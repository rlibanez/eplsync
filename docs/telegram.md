# Notificaciones de Telegram

En **Ajustes → Notificaciones → Telegram**, cada usuario puede configurar los avisos de
sus propias tareas programadas. Funcionan desde el backend aunque el navegador esté
cerrado. Se utiliza directamente la API oficial de Telegram: no necesitas otro
contenedor, Apprise, Shoutrrr ni un servidor de bots.

## Configuración

1. Crea un bot mediante [BotFather](https://t.me/BotFather), usando `/newbot`.
2. Copia su token en **Token del bot**. Necesitas `EPLSYNC_SECRET_KEY` configurada
   para guardarlo cifrado, igual que las demás credenciales de EPL Sync.
3. Abre una conversación con tu nuevo bot y pulsa **Iniciar** o envía `/start`.
4. Obtén el identificador numérico del chat mediante el método
   [getUpdates](https://core.telegram.org/bots/api#getupdates) de la API de Telegram:
   busca `message.chat.id` en la respuesta después de escribir al bot. **No uses
   el `id` de `getMe`: ese es el identificador del bot, no el de tu conversación.** Para un
   grupo puede ser negativo. No compartas el token con bots de terceros.
5. Introduce ese valor en **ID del chat**, prueba el envío y, cuando funcione, activa Telegram y guarda.
6. Pulsa **Enviar mensaje de prueba**. La prueba usa los valores del formulario sin guardarlos. Si dejas el token vacío, utiliza el token guardado de tu cuenta. El botón de prueba también funciona con las notificaciones desactivadas.

No se reciben ni ejecutan comandos desde Telegram. `/start` solo permite al bot
comenzar a enviarte mensajes. Si usas ese bot con otro programa que consume sus
mensajes, `getUpdates` puede no mostrar la conversación o entrar en conflicto con
su webhook; utiliza preferentemente un bot dedicado a EPL Sync.

El token guardado nunca vuelve al navegador: dejar el campo vacío lo conserva.
**Eliminar el token guardado** desactiva Telegram y borra esa credencial.
Conserva `EPLSYNC_SECRET_KEY` al actualizar o restaurar una copia de seguridad.

## Avisos y entrega

- Por defecto se notifican fallos y resultados parciales o que requieren intervención.
- Puedes activar también las operaciones completadas correctamente, incluidas las búsquedas que terminan sin encontrar actualizaciones.
- Los trabajos de descargas y Calibre se resumen por trabajo, no por libro.
- Las tareas y sus trabajos registran únicamente fallos o resultados parciales en Eventos, tanto por horario como mediante «Ejecutar ahora».
- Las actualizaciones del catálogo son la excepción: se registran siempre como «Actualización del catálogo», sin duplicarlas con «Tarea programada».
- Los trabajos automáticos pueden notificar su finalización correcta por Telegram sin añadir Eventos de éxito. La planificación de un ciclo no se anuncia como si sus trabajos ya hubieran terminado.
- No se notifican operaciones iniciadas directamente desde otras vistas, operaciones ajenas ni Eventos de seguridad. Se comprueban los
  permisos actuales del destinatario antes de entregar el mensaje.
- Los mensajes incluyen el nombre de la tarea, el resultado, identificadores, cantidades y motivos de fallo resumidos y filtrados para ocultar credenciales.
- Cada tarea permite activar o desactivar sus avisos de Telegram. Se notifican las ejecuciones por horario y mediante «Ejecutar ahora», respetando además los resultados habilitados en tu cuenta.
- Los mensajes no incluyen enlaces. El nombre de la tarea aparece en negrita junto a ✅, ⚠️ o ❌; después se muestran el origen («ejecución programada» o «ejecución manual»), el ID y el resultado, seguidos del motivo si lo hay y los contadores en líneas separadas con viñetas.

La cola persiste en SQLite y admite hasta 100 avisos pendientes por usuario. Se
procesa en un trabajador independiente, con tiempos máximos de conexión y envío.
Las respuestas de límite de frecuencia o error de servidor se reintentan hasta
un máximo de tres intentos. Los mensajes pendientes caducan a las 24 horas y los
registros de entrega se eliminan después de siete días.

Telegram no proporciona una clave de idempotencia para estos envíos. Ante un
error de transporte o un cierre durante el envío, EPL Sync lo marca como no
entregado y no lo repite automáticamente, aunque Telegram podría haberlo recibido.
La deduplicación de la cola evita repetir el mismo resultado de una operación;
no constituye una garantía de entrega exactamente una vez por parte de Telegram.

Los fallos de entrega no crean nuevos Eventos ni afectan al trabajo original.
En Ajustes se muestran los avisos pendientes y los no entregados conservados.
Cambiar la configuración cancela los mensajes aún pendientes; un envío que ya
esté en curso puede terminar con el destino que tenía antes del cambio.

La API de Telegram debe ser accesible por HTTPS desde el servidor o contenedor.
No hace falta publicar EPL Sync en Internet ni abrir nuevos puertos.

Los inicios de tareas pueden mostrarse como avisos flotantes en el navegador, según los ajustes de avisos flotantes e inicios de ejecución. Son avisos transitorios: no añaden filas a Eventos ni incrementan su contador de pendientes, y no se envían a Telegram.
