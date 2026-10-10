# Simulación de usuarios concurrentes

## Alcance

La prueba `ConcurrentUserLoadTests` arranca Tomcat en un puerto aleatorio y utiliza
peticiones HTTP reales con diez cuentas USER, cada una con su propio cliente,
cookies y sesión. No utiliza mocks de la API ni la base de la instalación.

La base temporal contiene 73.000 libros, sinopsis de aproximadamente 1.000
caracteres, 6.000 autores (incluyendo coautorías), 500 colecciones y 146.000
registros históricos de descarga. Todos los usuarios tienen CATALOG_READ;
la mitad también BOOK_HISTORY_READ, por lo que algunas respuestas del catálogo
incluyen el historial resumido.

SQLite mantiene la configuración de producción: WAL, synchronous=FULL, claves
foráneas y dos conexiones. La JVM se limita a 512 MiB de heap. Los datos se
preparan antes de medir: no se ejecutan importaciones, envíos ni tareas de
limpieza durante los escenarios.

Se alternan estas acciones:

- Navegación por páginas de 50 libros, ordenados por título.
- Páginas profundas del catálogo (entre las páginas 900 y 1.099).
- Búsquedas por título y autor, con consultas de autocompletado.
- Navegación y búsqueda en el Directorio de autores.
- Directorio de géneros y fichas individuales de libros.

Cada usuario espera entre uno y dos segundos entre acciones. Las acciones de
búsqueda pueden efectuar dos peticiones consecutivas: sugerencias y resultados.
No se reintentan errores automáticamente. Primero se recorren las rutas y se
registran sus tiempos en frío; después se miden un usuario durante 15 segundos,
cinco durante 60 segundos y diez durante 60 segundos.

## Reproducción

Desde `backend/`:

```bash
./mvnw -Deplsync.test.user-load=true -Dtest=ConcurrentUserLoadTests \
  -DargLine=-Xmx512m test
```

La prueba está desactivada en la suite ordinaria. La base y los archivos asociados
se eliminan al finalizar. El informe de la última ejecución queda en
`backend/target/user-load/results.json`, con tiempos por operación, percentiles,
códigos HTTP, ejemplos de errores, heap muestreado y CPU del proceso. El log
principal de esta prueba se escribe en `backend/target/user-load/eplsync.log`.

La prueba comprueba respuestas HTTP 200 y el contrato básico de los resultados.
No exige un tiempo máximo de respuesta como aserción de rendimiento; falla ante
errores HTTP, respuestas inválidas o resultados de catálogo inesperadamente vacíos.

## Interpretación

Los tiempos incluyen conexión HTTP local, autenticación de la sesión, espera por
conexiones SQLite, consultas y serialización de JSON. No incluyen el renderizado
de React, descarga de portadas, red pública ni Traefik. Las peticiones de una
misma cuenta son secuenciales; esta prueba no reproduce todos los posibles
abanicos de peticiones paralelas de un navegador.

El percentil 95 (p95) indica el tiempo dentro del cual termina el 95 % de las
peticiones observadas. El heap muestreado incluye toda la JVM de pruebas y memoria
pendiente de recolección; no equivale al RSS ni a memoria retenida. CPU se expresa
como núcleos ocupados de media, no como porcentaje de toda la máquina.

Una ejecución satisfactoria demuestra que esa carga concreta funciona en el
equipo probado. No establece una capacidad máxima de usuarios ni garantiza los
mismos tiempos con otros datos, máquinas, pausas entre acciones o tareas activas.

## Resultados del 10 de octubre de 2026

Ejecución local en un Intel Core i7-13700K (24 procesadores lógicos), con Java 25
y heap máximo de 512 MiB.

| Usuarios simultáneos | Peticiones | Errores | Mediana | p95 | Máximo |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 15 | 0 | 18.8 ms | 89.3 ms | 89.3 ms |
| 5 | 276 | 0 | 18.3 ms | 77.9 ms | 91.4 ms |
| 10 | 558 | 0 | 14.8 ms | 75.5 ms | 92.1 ms |

Todas las peticiones medidas devolvieron HTTP 200. No hubo errores de sesión,
conexiones SQLite ni rechazos por límite de consultas. El máximo de heap
muestreado en los escenarios fue 126.8 MiB. En el escenario de diez
usuarios, la CPU media del proceso fue 0.31 núcleos.

El escenario de un usuario es una referencia breve, no una comparación
estadística extensa. El orden de los escenarios permite que la JVM continúe
calentándose, por lo que no debe interpretarse una pequeña reducción de tiempos
como una mejora causada por añadir usuarios.

## Diez usuarios dedicados exclusivamente a búsquedas del Catálogo

Esta prueba adicional utiliza las mismas cuentas y volumen de datos. Todas las
peticiones medidas consultan `/api/catalog/books`; no incluye Directorio,
autocompletado ni fichas individuales. Alterna búsquedas por título, por autor y
combinadas con límites del número de páginas del libro. Las páginas de resultados tienen
50 libros y se ordenan por título o autor antes de paginar.

Se ejecutan dos escenarios:

1. Diez usuarios buscando durante 60 segundos, con pausas de 1–2 segundos entre
   búsquedas y consultas variadas.
2. Veinte tandas de diez búsquedas liberadas mediante una barrera de inicio, sin
   escalonar su lanzamiento. Cada tanda termina antes de iniciar la siguiente;
   entre tandas hay una pausa de 250 ms. Se mide también la diferencia entre las
   marcas de lanzamiento de los diez clientes.

La barrera sincroniza el envío desde los clientes. No significa que SQLite
atienda diez consultas a la vez: se conservan el pool de dos conexiones, el
límite de cuatro operaciones costosas y la espera limitada de la aplicación.
No se reintentan respuestas rechazadas.

Para ejecutar únicamente estos escenarios desde `backend/`:

```bash
./mvnw -Deplsync.test.user-load=true \
  '-Dtest=ConcurrentUserLoadTests#simulateTenCatalogSearchUsersAndSimultaneousBursts' \
  -DargLine=-Xmx512m test
```

El informe separado queda en
`backend/target/user-load/catalog-search-results.json`.

Resultados de la ejecución adicional del 10 de octubre de 2026, en el mismo
equipo y con heap máximo de 512 MiB:

| Escenario | Búsquedas | Errores | Mediana | p95 | Máximo |
| --- | ---: | ---: | ---: | ---: | ---: |
| 10 usuarios, pausas de 1–2 s, durante 60 s | 398 | 0 | 28.3 ms | 54.3 ms | 100.4 ms |
| 20 tandas de 10 búsquedas simultáneas | 200 | 0 | 62.9 ms | 98.7 ms | 114.3 ms |

Las 598 búsquedas devolvieron HTTP 200, sin errores de contenido,
rechazos HTTP 429 ni fallos de conexión. La mayor separación entre las marcas
de lanzamiento dentro de una tanda fue 1.04 ms. El escenario de 60 segundos
alcanzó 137.8 MiB de heap muestreado y una ocupación media de CPU
de 0.31 núcleos. No son mediciones de una carga sostenida sin pausas
ni de la capacidad máxima del servidor.
