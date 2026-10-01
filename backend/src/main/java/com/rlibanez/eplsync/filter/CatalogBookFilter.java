package com.rlibanez.eplsync.filter;

import com.rlibanez.eplsync.model.enums.BookStatus;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.model.enums.PublicationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
    @Min(1)
    private Long eplId;

    // --- Texto (contains, case-insensitive donde aplique) ---
    @Size(max = 255)
    private String author;

    @Size(max = 512)
    private String title;

    @Size(max = 512)
    private String genres;

    @Size(max = 255)
    private String collection;

    // --- Rangos numéricos ---
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
    private Language language;
    private PublicationStatus publicationStatus;

    /**
     * Permite filtrar por uno o varios estados:
     * ?status=DISPONIBLE&status=VERIFICADO
     */
    private Set<BookStatus> status;

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
        if (publicationYearFrom != null && publicationYearTo != null && publicationYearFrom > publicationYearTo)
            throw new IllegalArgumentException("publicationYearFrom debe ser <= publicationYearTo");
        if (publicationDateFrom != null && publicationDateTo != null && publicationDateFrom.isAfter(publicationDateTo))
            throw new IllegalArgumentException("publicationDateFrom debe ser <= publicationDateTo");
        if (insertDateFrom != null && insertDateBefore != null && !insertDateFrom.isBefore(insertDateBefore))
            throw new IllegalArgumentException("insertDateFrom debe ser anterior a insertDateBefore");
        author = norm(author);
        title = norm(title);
        genres = norm(genres);
        collection = norm(collection);
    }

    private String norm(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}