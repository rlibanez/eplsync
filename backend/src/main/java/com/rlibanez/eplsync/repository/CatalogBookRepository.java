package com.rlibanez.eplsync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.rlibanez.eplsync.model.CatalogBook;

/**
 * Repositorio para gestionar los libros del catálogo de ePubLibre.
 */
@Repository
public interface CatalogBookRepository extends JpaRepository<CatalogBook, Long> {

    /**
     * Busca libros por título (búsqueda parcial, case-insensitive).
     */
    List<CatalogBook> findByTitleContainingIgnoreCase(String title);

    /**
     * Busca libros por autor (búsqueda parcial, case-insensitive).
     */
    List<CatalogBook> findByAuthorContainingIgnoreCase(String author);

    /**
     * Busca libros por género (búsqueda parcial).
     */
    List<CatalogBook> findByGenresContaining(String genre);
}
