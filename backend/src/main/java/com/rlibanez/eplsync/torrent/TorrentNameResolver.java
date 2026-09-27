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
            .collect(Collectors.toUnmodifiableMap(java.beans.PropertyDescriptor::getName,
                    java.beans.PropertyDescriptor::getReadMethod));

    public static void validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("rename.pattern no puede estar vacío ni contener caracteres de control");
        }
        Matcher matcher = TOKEN.matcher(pattern);
        while (matcher.find()) {
            if (!FIELDS.containsKey(matcher.group(1))) {
                throw new IllegalArgumentException("rename.pattern contiene un campo que no existe en CatalogBook: " + matcher.group(1));
            }
        }
        String literals = matcher.replaceAll("");
        if (literals.contains("{") || literals.contains("}")) {
            throw new IllegalArgumentException("rename.pattern tiene llaves inválidas");
        }
    }

    public String resolve(String pattern, CatalogBook book) {
        validatePattern(pattern);
        Objects.requireNonNull(book, "El libro es obligatorio");
        String name = TOKEN.matcher(pattern).replaceAll(match ->
                Matcher.quoteReplacement(format(read(FIELDS.get(match.group(1)), book))));
        name = name.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ").strip();
        if (name.isBlank()) throw new IllegalArgumentException("El patrón genera un nombre de torrent vacío");
        return name;
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
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        return value.toString();
    }
}
