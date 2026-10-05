package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.model.CatalogBook;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Sustitución de campos del modelo, sin expresiones ni navegación por objetos. */
@Component
public class TorrentNameResolver {
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}");
    private static final Map<String, Method> FIELDS = Arrays.stream(CatalogBook.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .map(field -> BeanUtils.getPropertyDescriptor(CatalogBook.class, field.getName()))
            .filter(Objects::nonNull).filter(property -> property.getReadMethod() != null)
            .collect(Collectors.toUnmodifiableMap(property -> Objects.requireNonNull(property).getName(),
                    property -> Objects.requireNonNull(property).getReadMethod()));

    public static void validatePattern(String pattern) {
        validatePattern(pattern, "rename.pattern");
    }

    private static void validatePattern(String pattern, String label) {
        if (pattern == null || pattern.isBlank() || pattern.chars().anyMatch(Character::isISOControl)) {
            throw new com.rlibanez.eplsync.exception.UserInputException(label + " no puede estar vacío ni contener caracteres de control");
        }
        Matcher matcher = TOKEN.matcher(pattern);
        while (matcher.find()) {
            if (!FIELDS.containsKey(matcher.group(1))) {
                throw new com.rlibanez.eplsync.exception.UserInputException(label + " contiene un campo que no existe en CatalogBook: " + matcher.group(1));
            }
        }
        String literals = matcher.replaceAll("");
        if (literals.contains("{") || literals.contains("}")) {
            throw new com.rlibanez.eplsync.exception.UserInputException(label + " tiene llaves inválidas");
        }
    }

    public String resolve(String pattern, CatalogBook book) {
        validatePattern(pattern);
        String name = expand(pattern, book);
        name = name.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ").strip();
        if (name.isBlank()) throw new com.rlibanez.eplsync.exception.UserInputException("El patrón genera un nombre de torrent vacío");
        return name;
    }

    /** Cada entrada sigue siendo una etiqueta; nunca se divide por comas. */
    public java.util.List<String> resolveTags(java.util.List<String> patterns, CatalogBook book) {
        if (patterns == null) throw new com.rlibanez.eplsync.exception.UserInputException("tags debe ser una lista");
        var tags = new java.util.LinkedHashSet<String>();
        for (String pattern : patterns) {
            if (pattern == null) throw new com.rlibanez.eplsync.exception.UserInputException("tags no admite elementos null");
            if (pattern.isBlank() && pattern.codePoints().noneMatch(Character::isISOControl)) continue;
            validatePattern(pattern, "tags");
            String tag = expand(pattern, book).strip();
            if (tag.contains(",") || tag.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR))
                throw new com.rlibanez.eplsync.exception.UserInputException("Las etiquetas resueltas no pueden contener comas ni caracteres de control");
            if (!tag.isBlank()) tags.add(tag);
        }
        return java.util.List.copyOf(tags);
    }

    private String expand(String pattern, CatalogBook book) {
        Objects.requireNonNull(book, "El libro es obligatorio");
        return TOKEN.matcher(pattern).replaceAll(match ->
                Matcher.quoteReplacement(format(read(FIELDS.get(match.group(1)), book))));
    }

    private Object read(Method getter, CatalogBook book) {
        try {
            return getter.invoke(book);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("No se pudo leer un campo de CatalogBook", ex);
        }
    }

    private String format(Object value) {
        if (value == null) return "";
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof com.rlibanez.eplsync.model.enums.Language language) return language.getIsoCode();
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        return value.toString();
    }
}
