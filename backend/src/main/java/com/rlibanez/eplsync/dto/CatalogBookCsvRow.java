package com.rlibanez.eplsync.dto;

import com.opencsv.bean.CsvBindByName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CatalogBookCsvRow {

    @CsvBindByName(column = "EPL Id")
    private Long eplId;

    @CsvBindByName(column = "Revisión")
    private Double revision;

    @CsvBindByName(column = "Autor")
    private String author;

    @CsvBindByName(column = "Título")
    private String title;

    @CsvBindByName(column = "Géneros")
    private String genres;

    @CsvBindByName(column = "Colección")
    private String collection;

    @CsvBindByName(column = "Volumen")
    private Double volume;

    @CsvBindByName(column = "Año publicación")
    private Integer publicationYear;

    @CsvBindByName(column = "Sinopsis")
    private String synopsis;

    @CsvBindByName(column = "Páginas")
    private Integer pages;

    @CsvBindByName(column = "Idioma")
    private String language;

    @CsvBindByName(column = "Publicado")
    private String publishedRaw;

    @CsvBindByName(column = "Estado")
    private String status;

    @CsvBindByName(column = "Valoración")
    private Double rating;

    @CsvBindByName(column = "Nº Votos")
    private Integer votesCount;

    @CsvBindByName(column = "Enlace(s)")
    private String links;
}
