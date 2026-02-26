package com.rlibanez.eplsync.dto;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        PageMeta meta) {
    public record PageMeta(
            int page,
            int size,
            long totalItems,
            int totalPages,
            boolean first,
            boolean last,
            boolean hasNext,
            boolean hasPrevious) {
    }
}