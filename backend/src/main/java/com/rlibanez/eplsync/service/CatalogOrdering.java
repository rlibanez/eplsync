package com.rlibanez.eplsync.service;

import org.springframework.data.domain.Sort;

/** Public catalog sort keys mapped to their database expressions before pagination. */
final class CatalogOrdering {
    private CatalogOrdering() {}

    static Sort normalize(Sort requested) {
        com.rlibanez.eplsync.config.QueryLimits.sort(requested);
        var allowed = java.util.Set.of("eplId", "title", "author", "revision", "genres", "collection", "volume",
                "publicationYear", "pages", "language", "publicationStatus", "publicationDate", "insertDate",
                "lastModifiedDate", "status", "rating", "votesCount", "coverAvailable");
        if (requested.stream().anyMatch(order -> !allowed.contains(order.getProperty())))
            throw new com.rlibanez.eplsync.exception.UserInputException("Campo de ordenación del catálogo inválido");
        var orders = requested.stream().map(order -> order.getProperty().equals("insertDate")
                ? order.withProperty("insertMinute") : order).toList();
        Sort result = Sort.by(orders);
        return requested.getOrderFor("eplId") == null ? result.and(Sort.by("eplId")) : result;
    }
}
