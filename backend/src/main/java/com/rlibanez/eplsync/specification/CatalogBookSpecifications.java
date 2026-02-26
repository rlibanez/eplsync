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

        spec = andIfNotNull(spec, authorContains(f.getAuthor()));
        spec = andIfNotNull(spec, titleContains(f.getTitle()));
        spec = andIfNotNull(spec, genresContains(f.getGenres()));
        spec = andIfNotNull(spec, collectionContains(f.getCollection()));
        spec = andIfNotNull(spec, publicationYearEqualsOrBetween(f));
        spec = andIfNotNull(spec, languageEquals(f));
        spec = andIfNotNull(spec, publicationStatusEquals(f));
        spec = andIfNotNull(spec, publicationDateEqualsOrBetween(f));
        spec = andIfNotNull(spec, statusIn(f));

        return spec;
    }

    private static Specification<CatalogBook> authorContains(String author) {
        if (author == null)
            return null;
        return (root, q, cb) -> cb.like(cb.lower(root.get("author")), "%" + author.toLowerCase() + "%");
    }

    private static Specification<CatalogBook> titleContains(String title) {
        if (title == null)
            return null;
        return (root, q, cb) -> cb.like(cb.lower(root.get("title")), "%" + title.toLowerCase() + "%");
    }

    private static Specification<CatalogBook> genresContains(String genres) {
        if (genres == null)
            return null;
        return (root, q, cb) -> cb.like(root.get("genres"), "%" + genres + "%");
    }

    private static Specification<CatalogBook> collectionContains(String collection) {
        if (collection == null)
            return null;
        return (root, q, cb) -> cb.like(cb.lower(root.get("collection")), "%" + collection.toLowerCase() + "%");
    }

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
        return (root, q, cb) -> cb.equal(root.get("language"), f.getLanguage());
    }

    private static Specification<CatalogBook> publicationStatusEquals(CatalogBookFilter f) {
        if (f.getPublicationStatus() == null)
            return null;
        return (root, q, cb) -> cb.equal(root.get("publicationStatus"), f.getPublicationStatus());
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