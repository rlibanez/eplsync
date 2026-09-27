package com.rlibanez.eplsync.dto;

import com.rlibanez.eplsync.model.CatalogBook;
import java.util.List;

/** Vista de una actualización; las fechas de auditoría nuevas se asignan al guardar. */
public record ImportPreviewResult(
        ImportResult summary,
        int page,
        int size,
        List<CatalogBook> createdBooks,
        List<BookUpdate> updatedBooks
) {
    public record BookUpdate(CatalogBook before, CatalogBook after, List<String> changedFields) {}
}
