# Identidad visual de EPL Sync

El símbolo es un gato dormido, con ojo cerrado y cola enroscada, sobre tres libros.
La forma identifica la aplicación en todas las paletas; solo cambia su color.

## Arte y variantes

Los maestros SVG se encuentran en `frontend/public/brand/`:

- `eplsync-empty-*`: opción B, libros vacíos. Predeterminada en la aplicación.
- `eplsync-lined-*`: opción A, una línea interior en cada libro. Conservada como alternativa.
- `eplsync-small-*`: adaptación para 16–32 px, sin líneas interiores y con separaciones iguales más abiertas.
- `eplsync-small-lined-*`: adaptación pequeña con líneas interiores, conservada como alternativa.
- Sufijos `black`, `white`, `green` y `adaptive`: negro, blanco, verde fijo y color heredado (`currentColor`).
- `eplsync-app-icon.svg`: icono independiente verde, con fondo y esquinas redondeadas.
- `eplsync-app-icon-192.png`, `eplsync-app-icon-512.png` y `eplsync-favicon.ico`: exportaciones raster para otros usos.

Los SVG tienen huecos transparentes reales, trazos convertidos a contornos y ninguna dependencia de fuente,
imagen raster, filtro o máscara. Los detalles originales del ojo y la cola se mantienen.
En el dibujo estándar los tres espacios verticales miden una unidad entre bordes visibles.
En el dibujo pequeño miden seis unidades para resistir la reducción.

## Integración

`BrandLogo` hereda `currentColor`, utiliza `--accent` y elige automáticamente el dibujo pequeño hasta 32 px.
Se usa en la barra lateral, cabecera móvil y acceso/registro. Acompaña al nombre accesible EPL Sync y es decorativo
para lectores de pantalla. Puede utilizarse `variant="empty"` (predeterminado) o `variant="lined"`; cada opción tiene su adaptación pequeña.

El favicon se genera con el dibujo pequeño y se recolorea desde `AppearanceProvider`; un único archivo atiende
las diez paletas y los dos modos. Fuera de la aplicación el icono conserva el verde de marca.

Las fórmulas existentes siguen siendo:

| Modo | Símbolo | Fondo del recuadro |
| --- | --- | --- |
| Claro | `hsl(hue 55% 29%)` | `hsl(hue 35% 90%)` |
| Oscuro | `hsl(hue 40% 76%)` | `hsl(hue 23% 20%)` |

La comprobación numérica de las veinte combinaciones da un contraste mínimo de 4,62:1 para estos recuadros.
Este resultado no se extiende automáticamente a cualquier fotografía o fondo arbitrario.

## Uso

Conservar proporciones, ojo cerrado, cola y número de libros. No estirar ni añadir sombras o degradados.
Usar el dibujo pequeño hasta 32 px y el estándar desde 48 px. Dejar un margen exterior de al menos 12 unidades
sobre el lienzo del símbolo al combinarlo con otros elementos. La versión blanca se destina a fondos oscuros,
la negra a claros y la verde a material independiente de las preferencias del usuario.

## Generación y comprobación

Ejecutar `python3 scripts/generate-brand-assets.py` desde el repositorio. Regenera los SVG y
`logoArtwork.ts` a partir de una misma geometría. Requiere `rsvg-convert` para la lámina de revisión;
las imágenes y el informe de contraste se guardan en `branding/final/` (carpeta local ignorada por Git).
Después, formatear `frontend/src/components/logoArtwork.ts` con Prettier.

La prueba `frontend/e2e/branding.spec.ts` verifica el cambio real de las veinte combinaciones, favicon,
transparencias, persistencia de preferencias y presencia del símbolo en móvil.

## Copias para futuras ediciones

El generador guarda ambos maestros editables en `branding/final/eplsync-sin-linea.svg` y
`branding/final/eplsync-con-linea.svg`, junto con sus adaptaciones `-small.svg`.
Estos archivos locales no son necesarios para ejecutar la aplicación y siguen excluidos de Git.
