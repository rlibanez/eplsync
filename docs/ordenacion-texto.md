# Ordenación alfabética de las tablas

Los textos se ordenan siguiendo el alfabeto español, independientemente del
idioma del navegador o de la máquina que ejecuta el servidor:

- Tildes, mayúsculas y minúsculas no crean grupos separados.
- Ñ se sitúa después de N y antes de O.
- Los signos y espacios iniciales se omiten para ordenar: `¿Quién?` va en Q.
- Los números dentro del texto siguen orden natural: `Libro 2` precede a `Libro 10`.
- Los valores originales se conservan; no se modifican títulos ni autores.

El servidor aplica la comparación **antes de LIMIT/OFFSET** y conserva los
criterios secundarios y los desempates existentes por ID/posición. Los campos
numéricos, fechas y filtros mantienen su comportamiento.

JPA utiliza expresiones COLLATE a través de Criteria/HQL. Las consultas SQL y
los informes temporales utilizan la misma comparación, llamada `EPL_TEXT`.
Esta se registra en cada conexión SQLite, incluidas las conexiones que Hikari
reemplaza. No se cambia la comparación predeterminada de columnas, las reglas de
igualdad ni las restricciones de unicidad de usuarios u otros identificadores.

En el navegador, las tablas de usuarios y los informes locales utilizan una
comparación española con Intl.Collator y la misma normalización de signos
iniciales. Java y el navegador tienen motores lingüísticos diferentes; las
pruebas de ambos verifican tildes, Ñ, signos iniciales, Unicode compuesto y
números. Las tablas siguen utilizando sus desempates existentes.

Los índices de título y autor con `EPL_TEXT` evitan ordenar todo el catálogo en
cada consulta. Se incluyen en la migración inicial `V1_0_0`, siguiendo el criterio
actual de instalaciones nuevas; la versión del esquema es `1.0.0`
y no se añade una migración de actualización.

Al abrir la base mediante herramientas externas, esta colación
no estará registrada automáticamente: algunas operaciones sobre esos índices,
como escrituras o comprobaciones de integridad, requieren registrar la misma
comparación. No sustituirla por otra ni reconstruir esos índices con reglas
distintas. Si en el futuro cambia el comparador, habrá que reconstruir los
índices afectados mediante una migración.

Verificación realizada con catálogo sintético de 73.000 libros y heap de 512 MiB:
consultas por título de unos 2 ms y página 1.000 (50 libros) de unos 3 ms, después
del calentamiento. Son mediciones orientativas de esa carga, no garantías de
rendimiento para todas las búsquedas.
