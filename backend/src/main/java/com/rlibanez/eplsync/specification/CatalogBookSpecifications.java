package com.rlibanez.eplsync.specification;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;

import jakarta.persistence.criteria.Path;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

public final class CatalogBookSpecifications {

    private CatalogBookSpecifications() {
    }

    public static Specification<CatalogBook> fromFilter(CatalogBookFilter f) {

        Specification<CatalogBook> spec = (root, q, cb) -> cb.conjunction();

        if (f == null) {
            return spec;
        }

        f.normalize();
        if (f.getSelectedIds() != null) spec = spec.and((root,q,cb) -> f.getSelectedIds().length == 0
            ? cb.disjunction() : root.get("eplId").in((Object[]) f.getSelectedIds()));
        if (f.getExcludedIds() != null && f.getExcludedIds().length > 0)
            spec = spec.and((root,q,cb) -> cb.not(root.get("eplId").in((Object[]) f.getExcludedIds())));

        if (f.getEplId() != null) {
            spec = spec.and((root, q, cb) -> root.get("eplId").in((Object[]) f.getEplId()));
        }

        spec = andIfNotNull(spec, authorContains(f.getAuthor()));
        spec = andIfNotNull(spec, titleContains(f.getTitle()));
        spec = andIfNotNull(spec, genresContains(f.getGenres()));
        spec = andIfNotNull(spec, collectionContains(f.getCollection()));
        if (f.getRevision() != null) spec = spec.and((root, q, cb) -> root.get("revision").in((Object[]) f.getRevision()));
        spec = andIfNotNull(spec, publicationYearEqualsOrBetween(f));
        spec = andIfNotNull(spec, languageEquals(f));
        spec = andIfNotNull(spec, publicationStatusEquals(f));
        spec = andIfNotNull(spec, publicationDateEqualsOrBetween(f));
        spec = andIfNotNull(spec, statusIn(f));

        if (f.getInsertDateFrom() != null)
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("insertDate"), f.getInsertDateFrom()));
        if (f.getInsertDateBefore() != null)
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("insertDate"), f.getInsertDateBefore()));
        return spec;
    }

    private static Specification<CatalogBook> containsAny(String field, String[] values) {
        if (values == null) return null;
        return (root, q, cb) -> cb.or(java.util.Arrays.stream(values)
            .map(value -> cb.like(cb.lower(root.get(field)), "%" + value.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", '\\'))
            .toArray(jakarta.persistence.criteria.Predicate[]::new));
    }
    private static Specification<CatalogBook> authorContains(String[] values) { return containsAny("author", values); }
    private static Specification<CatalogBook> titleContains(String[] values) { return containsAny("title", values); }
    private static Specification<CatalogBook> genresContains(String[] values) { return containsAny("genres", values); }
    private static Specification<CatalogBook> collectionContains(String[] values) { return containsAny("collection", values); }

    private static Specification<CatalogBook> publicationYearEqualsOrBetween(CatalogBookFilter f) {
        Integer y = f.getPublicationYear();
        Integer from = f.getPublicationYearFrom();
        Integer to = f.getPublicationYearTo();

        if (y != null) {
            return (root, q, cb) -> cb.equal(root.get("publicationYear"), y);
        }
        if (from == null && to == null)
            return null;

        return (root, q, cb) -> {
            Path<Integer> path = root.get("publicationYear");
            if (from != null && to != null)
                return cb.between(path, from, to);
            if (from != null)
                return cb.greaterThanOrEqualTo(path, from);
            return cb.lessThanOrEqualTo(path, to);
        };
    }

    private static Specification<CatalogBook> languageEquals(CatalogBookFilter f) {
        if (f.getLanguage() == null)
            return null;
        return (root, q, cb) -> root.get("language").in((Object[]) f.getLanguage());
    }

    private static Specification<CatalogBook> publicationStatusEquals(CatalogBookFilter f) {
        if (f.getPublicationStatus() == null)
            return null;
        return (root, q, cb) -> root.get("publicationStatus").in((Object[]) f.getPublicationStatus());
    }

    private static Specification<CatalogBook> publicationDateEqualsOrBetween(CatalogBookFilter f) {
        var d = f.getPublicationDate();
        var from = f.getPublicationDateFrom();
        var to = f.getPublicationDateTo();

        if (d != null) {
            return (root, q, cb) -> cb.equal(root.get("publicationDate"), d);
        }
        if (from == null && to == null)
            return null;

        return (root, q, cb) -> {
            Path<LocalDate> path = root.get("publicationDate");
            if (from != null && to != null)
                return cb.between(path, from, to);
            if (from != null)
                return cb.greaterThanOrEqualTo(path, from);
            return cb.lessThanOrEqualTo(path, to);
        };
    }

    private static Specification<CatalogBook> statusIn(CatalogBookFilter f) {
        if (f.getStatus() == null || f.getStatus().isEmpty())
            return null;
        return (root, q, cb) -> root.get("status").in(f.getStatus());
    }

    private static Specification<CatalogBook> andIfNotNull(
            Specification<CatalogBook> base,
            Specification<CatalogBook> other) {
        if (other == null)
            return base;
        return base.and(other);
    }
}