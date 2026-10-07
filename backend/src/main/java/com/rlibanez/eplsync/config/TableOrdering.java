package com.rlibanez.eplsync.config;

import com.rlibanez.eplsync.exception.UserInputException;
import org.springframework.data.domain.Sort;
import java.util.*;

/** Validated table criteria; never concatenate unvalidated fields into queries. */
public final class TableOrdering {
    private TableOrdering() {}
    public static List<Sort.Order> parse(String value, Set<String> fields) {
        if (value == null) throw new UserInputException("Ordenación inválida");
        var criteria = value.split(";", -1);
        if (criteria.length > QueryLimits.MAX_SORT_FIELDS) throw new UserInputException("Máximo de 8 criterios de ordenación");
        var used = new HashSet<String>();
        var result = new ArrayList<Sort.Order>();
        for (var criterion : criteria) {
            var parts = criterion.split(",", -1);
            if (parts.length != 2 || !fields.contains(parts[0]) || !used.add(parts[0])
                    || !Set.of("asc", "desc").contains(parts[1].toLowerCase(Locale.ROOT)))
                throw new UserInputException("Ordenación inválida: campo o sentido desconocido, o criterio repetido");
            result.add(new Sort.Order(Sort.Direction.fromString(parts[1]), parts[0]));
        }
        return result;
    }
    public static String request(jakarta.servlet.http.HttpServletRequest request, String fallback) {
        var values = request.getParameterValues("sort");
        return values == null ? fallback : String.join(";", values);
    }
    public static void validateParameters(jakarta.servlet.http.HttpServletRequest request) {
        request.getParameterMap().forEach((key, values) -> {
            if ((!key.equals("sort") && values.length != 1) || Arrays.stream(values).anyMatch(String::isBlank))
                throw new UserInputException("Parámetro vacío o repetido");
        });
    }
}
