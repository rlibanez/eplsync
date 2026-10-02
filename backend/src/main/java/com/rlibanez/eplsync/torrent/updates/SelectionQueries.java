package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import java.util.*;

/** Strict query validation prevents misspelled filters from widening a submission. */
final class SelectionQueries {
    private SelectionQueries() {}

    static <T> PageResponse<T> page(List<T> result, int page, int size) {
        if (page < 0 || size < 1) throw new IllegalArgumentException("page >= 0 y size > 0");
        long offset = (long) page * size;
        var selected = offset >= result.size() ? List.<T>of()
                : result.subList((int) offset, (int) Math.min(offset + size, result.size()));
        int pages = (int) ((result.size() + (long) size - 1) / size);
        return new PageResponse<>(selected, new PageResponse.PageMeta(page, size, result.size(), pages,
                page == 0, page >= pages - 1, page < pages - 1, page > 0));
    }

    static String safeLog(Object value) {
        String text = String.valueOf(value).replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        return text.substring(0, Math.min(text.length(), 2000));
    }
}
