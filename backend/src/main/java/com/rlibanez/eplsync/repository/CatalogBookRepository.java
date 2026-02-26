package com.rlibanez.eplsync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.rlibanez.eplsync.model.CatalogBook;

/**
 * Repositorio para gestionar los libros del catálogo de ePubLibre.
 */
@Repository
public interface CatalogBookRepository extends JpaRepository<CatalogBook, Long>, JpaSpecificationExecutor<CatalogBook> {

}
