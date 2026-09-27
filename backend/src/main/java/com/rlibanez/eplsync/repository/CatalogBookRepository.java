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

    interface TorrentIdentity {
        Long getEplId();
        Double getRevision();
        String getLinks();
    }

    @org.springframework.data.jpa.repository.Query("select b.eplId as eplId, b.revision as revision, b.links as links from CatalogBook b")
    java.util.List<TorrentIdentity> findTorrentIdentities();
}
