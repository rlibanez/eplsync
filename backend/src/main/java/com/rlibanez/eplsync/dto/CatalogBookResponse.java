package com.rlibanez.eplsync.dto;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.*;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import java.time.LocalDate;
import java.util.List;

/** Respuesta API: los datos de descarga no se persisten en CatalogBook. */
public record CatalogBookResponse(
        Long eplId,
        Double revision,
        String author,
        String title,
        String genres,
        String collection,
        Double volume,
        Integer publicationYear,
        String synopsis,
        Integer pages,
        Language language,
        PublicationStatus publicationStatus,
        LocalDate publicationDate,
        LocalDate insertDate,
        LocalDate lastModifiedDate,
        BookStatus status,
        Double rating,
        Integer votesCount,
        String links,
        Download download) {
    public record Download(List<DownloadItem> items) {}
    public record DownloadItem(String id, Double revision, DownloadStatus status, boolean completed) {}
    public static CatalogBookResponse from(CatalogBook book, Download download) {
        return new CatalogBookResponse(
                book.getEplId(),
                book.getRevision(),
                book.getAuthor(),
                book.getTitle(),
                book.getGenres(),
                book.getCollection(),
                book.getVolume(),
                book.getPublicationYear(),
                book.getSynopsis(),
                book.getPages(),
                book.getLanguage(),
                book.getPublicationStatus(),
                book.getPublicationDate(),
                book.getInsertDate(),
                book.getLastModifiedDate(),
                book.getStatus(),
                book.getRating(),
                book.getVotesCount(),
                book.getLinks(), download);
    }
}
