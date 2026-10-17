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
            ? cb.disjunction() : selectedIds(root,cb,f.getSelectedIds()));
        if (f.getExcludedIds() != null && f.getExcludedIds().length > 0)
            spec = spec.and((root,q,cb) -> cb.not(selectedIds(root,cb,f.getExcludedIds())));

        if (f.getEplId() != null) {
            spec = spec.and((root, q, cb) -> root.get("eplId").in((Object[]) f.getEplId()));
        }

        spec = andIfNotNull(spec, textFilter("author",f.getAuthor(),f.getAuthorOperator()));
        spec = andIfNotNull(spec, textFilter("title",f.getTitle(),f.getTitleOperator()));
        spec = andIfNotNull(spec, genresContains(f.getGenres()));
        spec = andIfNotNull(spec, collectionContains(f.getCollection()));
        if (f.getRevision() != null) spec = spec.and((root, q, cb) -> root.get("revision").in((Object[]) f.getRevision()));
        spec = andIfNotNull(spec, publicationYearEqualsOrBetween(f));
        spec = andIfNotNull(spec, languageEquals(f));
        spec = andIfNotNull(spec, publicationStatusEquals(f));
        spec = andIfNotNull(spec, publicationDateEqualsOrBetween(f));
        spec = andIfNotNull(spec, statusIn(f));

        if (f.getPagesFrom() != null)
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("pages"), f.getPagesFrom()));
        if (f.getPagesTo() != null)
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("pages"), f.getPagesTo()));
        if (f.getInsertDateFrom() != null)
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("insertDate"), f.getInsertDateFrom()));
        if (f.getInsertDateBefore() != null)
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("insertDate"), f.getInsertDateBefore()));
        return spec;
    }

    private static Specification<CatalogBook> containsAny(String field, String[] values) {
        if (values == null) return null;
        return (root, q, cb) -> cb.or(java.util.Arrays.stream(values)
            .map(value -> cb.like(cb.lower(cb.coalesce(root.<String>get(field),"")), "%" + value.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", '\\'))
            .toArray(jakarta.persistence.criteria.Predicate[]::new));
    }
    private static Specification<CatalogBook> textFilter(String field,String[] values,String operator) {
        var spec=containsAny(field,values);
        return spec==null || !com.rlibanez.eplsync.ordering.StateOperator.excluded(operator)?spec:Specification.not(spec);
    }
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
        return (root, q, cb) -> com.rlibanez.eplsync.ordering.StateOperator.excluded(f.getLanguageOperator()) ? cb.or(cb.isNull(root.get("language")),cb.not(root.get("language").in((Object[]) f.getLanguage()))) : root.get("language").in((Object[]) f.getLanguage());
    }

    private static Specification<CatalogBook> publicationStatusEquals(CatalogBookFilter f) {
        if (f.getPublicationStatus() == null)
            return null;
        return (root, q, cb) -> com.rlibanez.eplsync.ordering.StateOperator.excluded(f.getPublicationStatusOperator()) ? cb.not(root.get("publicationStatus").in((Object[]) f.getPublicationStatus())) : root.get("publicationStatus").in((Object[]) f.getPublicationStatus());
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
        return (root, q, cb) -> com.rlibanez.eplsync.ordering.StateOperator.excluded(f.getStatusOperator()) ? cb.not(root.get("status").in(f.getStatus())) : root.get("status").in(f.getStatus());
    }

    private static Specification<CatalogBook> andIfNotNull(
            Specification<CatalogBook> base,
            Specification<CatalogBook> other) {
        if (other == null)
            return base;
        return base.and(other);
    }
    private static jakarta.persistence.criteria.Predicate selectedIds(jakarta.persistence.criteria.Root<CatalogBook> root,jakarta.persistence.criteria.CriteriaBuilder cb,Long[] ids) {
        if(ids.length<=1000) return root.get("eplId").in((Object[])ids);
        // One JSON parameter avoids both SQLite's variable limit and a huge Criteria tree.
        var criteria=(org.hibernate.query.criteria.HibernateCriteriaBuilder)cb;
        String selected=java.util.Arrays.stream(ids).map(String::valueOf).collect(java.util.stream.Collectors.joining(",","[","]"));
        return cb.isTrue(criteria.sql("? in (select cast(value as integer) from json_each(?))",Boolean.class,root.get("eplId"),criteria.value(selected)));
    }

}