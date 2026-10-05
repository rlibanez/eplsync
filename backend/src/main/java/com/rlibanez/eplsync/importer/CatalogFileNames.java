package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.exception.CatalogValidationException;
import java.text.Normalizer;

/** Original names are display metadata only, never filesystem paths or shell arguments. */
public final class CatalogFileNames {
    private static final int MAX_DISPLAY_LENGTH = 200;
    private CatalogFileNames() {}

    public static String display(String value, String fallback) {
        if (value == null) return fallback;
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        var result = new StringBuilder();
        Normalizer.normalize(name, Normalizer.Form.NFC).codePoints()
            .filter(c -> !unsafe(c)).limit(MAX_DISPLAY_LENGTH).forEach(result::appendCodePoint);
        String cleaned = result.toString().strip();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? fallback : cleaned;
    }

    static void validateEntry(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.codePoints().anyMatch(CatalogFileNames::unsafe))
            throw new CatalogValidationException("El ZIP contiene un nombre de archivo inválido o demasiado largo");
        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.indexOf(':') >= 0)
            throw new CatalogValidationException("El ZIP contiene una ruta absoluta no permitida");
        for (String part : path.split("/")) {
            if (part.equals("..") || part.equals("."))
                throw new CatalogValidationException("El ZIP contiene una ruta de archivo no permitida");
        }
        String basename = path.substring(path.lastIndexOf('/') + 1);
        if (basename.codePointCount(0, basename.length()) > MAX_DISPLAY_LENGTH)
            throw new CatalogValidationException("El ZIP contiene un nombre de archivo demasiado largo (máximo 200 caracteres)");
    }

    private static boolean unsafe(int c) {
        int type = Character.getType(c);
        return Character.isISOControl(c) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE;
    }
}
