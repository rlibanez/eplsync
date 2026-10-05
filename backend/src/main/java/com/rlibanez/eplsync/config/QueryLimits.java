package com.rlibanez.eplsync.config;

/** Limits shared by public list queries; offsets must fit JPA's integer API. */
public final class QueryLimits {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 1000;
    public static final int MAX_SORT_FIELDS = 8;
    public static final int MAX_SELECTION_IDS = 10000;
    private QueryLimits() {}

    public static void page(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE)
            throw new IllegalArgumentException("page debe ser >= 0 y size debe estar entre 1 y " + MAX_SIZE);
        if ((long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("La página solicitada supera el desplazamiento máximo permitido");
    }
    public static void sort(org.springframework.data.domain.Sort sort) {
        if (sort.stream().count() > MAX_SORT_FIELDS)
            throw new IllegalArgumentException("Máximo de " + MAX_SORT_FIELDS + " criterios de ordenación");
    }
}
