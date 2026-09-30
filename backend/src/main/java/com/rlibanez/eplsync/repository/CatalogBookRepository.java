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

    interface CoverIdentity {
        Long getEplId();
        String getCoverUrl();
        Boolean getCoverAvailable();
    }

    @org.springframework.data.jpa.repository.Query("select b.eplId as eplId, b.coverUrl as coverUrl, "
            + "b.coverAvailable as coverAvailable from CatalogBook b where b.eplId > :afterId "
            + "and (:eplId is null or b.eplId = :eplId) "
            + "and b.coverUrl is not null and trim(b.coverUrl) <> '' "
            + "and (:onlyUnchecked = false or b.coverAvailable is null) order by b.eplId")
    java.util.List<CoverIdentity> findCovers(long afterId, Long eplId, boolean onlyUnchecked,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select count(b) from CatalogBook b where b.eplId > :afterId "
            + "and (:eplId is null or b.eplId = :eplId) "
            + "and b.coverUrl is not null and trim(b.coverUrl) <> '' "
            + "and (:onlyUnchecked = false or b.coverAvailable is null)")
    long countCovers(long afterId, Long eplId, boolean onlyUnchecked);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CatalogBook b set b.coverAvailable = :available "
            + "where b.eplId = :id and b.coverUrl = :url "
            + "and (b.coverAvailable = :previous or (b.coverAvailable is null and :previous is null))")
    int updateCoverAvailability(long id, String url, Boolean previous, boolean available);

    interface TorrentIdentity {
        Long getEplId();
        Double getRevision();
        String getLinks();
    }

    @org.springframework.data.jpa.repository.Query("select b.eplId as eplId, b.revision as revision, b.links as links from CatalogBook b")
    java.util.List<TorrentIdentity> findTorrentIdentities();
    interface UpdateIdentity extends TorrentIdentity { String getTitle(); }

    @org.springframework.data.jpa.repository.Query("select b.eplId as eplId, b.revision as revision, "
            + "b.links as links, b.title as title from CatalogBook b where b.eplId in :ids")
    java.util.List<UpdateIdentity> findUpdateIdentities(java.util.List<Long> ids);

}
