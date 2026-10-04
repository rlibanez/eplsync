package com.rlibanez.eplsync.service;

import org.springframework.data.domain.Sort;

/** Public catalog sort keys mapped to their database expressions before pagination. */
final class CatalogOrdering {
    private CatalogOrdering() {}

    static Sort normalize(Sort requested) {
        var orders = requested.stream().map(order -> order.getProperty().equals("insertDate")
                ? order.withProperty("insertMinute") : order).toList();
        Sort result = Sort.by(orders);
        return requested.getOrderFor("eplId") == null ? result.and(Sort.by("eplId")) : result;
    }
}
