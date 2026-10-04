package com.rlibanez.eplsync.filter;

import java.util.Objects;

import com.rlibanez.eplsync.model.enums.BookStatus;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.model.enums.PublicationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Set;

/**
 * Filtro de búsqueda para CatalogBook (binding desde query params).
 *
 * Ejemplos:
 * /api/catalog/books?author=brandon&language=es
 * /api/catalog/books?title=mistborn&publicationYearFrom=2000&publicationYearTo=2015
 * /api/catalog/books?publicationDateFrom=2013-01-01&publicationDateTo=2013-12-31
 * /api/catalog/books?status=DISPONIBLE&status=VERIFICADO
 */
@Getter
@Setter
@ToString
public class CatalogBookFilter {

    // Identificador exacto; combinable con el resto de filtros.
    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private Long[] eplId;

    public void setEplId(Long... values) { this.eplId = values; }

    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private Double[] revision;

    public void setRevision(Double... values) { this.revision = values; }

    // --- Texto (contains, case-insensitive donde aplique) ---
    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private String[] author;

    public void setAuthor(String... values) { this.author = values; }

    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private String[] title;

    public void setTitle(String... values) { this.title = values; }

    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private String[] genres;

    public void setGenres(String... values) { this.genres = values; }

    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private String[] collection;

    public void setCollection(String... values) { this.collection = values; }

    // --- Rangos numéricos ---
    @Min(0)
    private Integer pagesFrom;

    @Min(0)
    private Integer pagesTo;

    @Min(0)
    @Max(3000)
    private Integer publicationYear;

    @Min(0)
    @Max(3000)
    private Integer publicationYearFrom;

    @Min(0)
    @Max(3000)
    private Integer publicationYearTo;

    // --- Enums / exact match ---
    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private Language[] language;

    public void setLanguage(Language... values) { this.language = values; }
    @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private PublicationStatus[] publicationStatus;

    public void setPublicationStatus(PublicationStatus... values) { this.publicationStatus = values; }

    /**
     * Permite filtrar por uno o varios estados:
     * ?status=DISPONIBLE&status=VERIFICADO
     */
    private Set<BookStatus> status;

    // Explicit catalog selections; an empty selectedIds array deliberately matches nothing.
    private Long[] selectedIds;
    private Long[] excludedIds;

    // --- Fechas ---
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate publicationDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate publicationDateFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate publicationDateTo;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private java.time.Instant insertDateFrom;

    // Exclusive upper bound: midnight after the last selected day.
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private java.time.Instant insertDateBefore;

    // --- Helper: normalización de strings (para evitar " ") ---
    public void normalize() {
        for (Long[] ids : new Long[][]{selectedIds, excludedIds})
            if (ids != null && java.util.Arrays.stream(ids).anyMatch(id -> id == null || id < 1))
                throw new IllegalArgumentException("Los identificadores seleccionados deben ser positivos");

        if (eplId != null && java.util.Arrays.stream(eplId).anyMatch(v -> v == null || v < 1))
            throw new IllegalArgumentException("eplId debe ser un entero mayor que cero");
        eplId = compact(eplId); revision = compact(revision);
        language = compact(language); publicationStatus = compact(publicationStatus);

        if (revision != null && java.util.Arrays.stream(revision).anyMatch(v -> v == null || !Double.isFinite(v) || v < 0))
            throw new IllegalArgumentException("revision debe ser un número finito mayor o igual a cero");
        if ((pagesFrom != null && pagesFrom < 0) || (pagesTo != null && pagesTo < 0))
            throw new IllegalArgumentException("Los límites de páginas deben ser >= 0");
        if (pagesFrom != null && pagesTo != null && pagesFrom > pagesTo)
            throw new IllegalArgumentException("pagesFrom debe ser <= pagesTo");
        if (publicationYearFrom != null && publicationYearTo != null && publicationYearFrom > publicationYearTo)
            throw new IllegalArgumentException("publicationYearFrom debe ser <= publicationYearTo");
        if (publicationDateFrom != null && publicationDateTo != null && publicationDateFrom.isAfter(publicationDateTo))
            throw new IllegalArgumentException("publicationDateFrom debe ser <= publicationDateTo");
        if (insertDateFrom != null && insertDateBefore != null && !insertDateFrom.isBefore(insertDateBefore))
            throw new IllegalArgumentException("insertDateFrom debe ser anterior a insertDateBefore");
        author = norm(author, 255);
        title = norm(title, 512);
        genres = norm(genres, 512);
        collection = norm(collection, 255);
    }

    private static <T> T[] compact(T[] values) {
        if (values == null || values.length == 0) return null;
        if (values.length > 100) throw new IllegalArgumentException("Máximo de 100 valores por campo");
        if (java.util.Arrays.stream(values).anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Los filtros no admiten valores null");
        return values;
    }
    private String[] norm(String[] values, int limit) {
        values = compact(values);
        if (values == null) return null;
        var result = java.util.Arrays.stream(values).map(value -> Objects.requireNonNull(value).trim()).filter(v -> !v.isEmpty()).distinct().toArray(String[]::new);
        if (java.util.Arrays.stream(result).anyMatch(v -> v.length() > limit))
            throw new IllegalArgumentException("Texto de filtro demasiado largo");
        return result.length == 0 ? null : result;
    }
}
