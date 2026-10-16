package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"CATALOG_READ","BOOK_HISTORY_READ","CALIBRE_READ","TORRENT_SEND","TORRENT_JOBS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.tasks.worker-enabled=false"})
@Transactional
class CatalogLibraryFilterTests {
    @Autowired CatalogBookRepository books;
    @Autowired CatalogBookService service;
    @Autowired com.rlibanez.eplsync.service.MagnetExportService exports;
    @Autowired DownloadTrackingService tracking;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM torrent_downloads");jdbc.update("DELETE FROM calibre_books");books.deleteAllInBatch();
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
        for(long id=1;id<=6;id++) books.save(CatalogBook.builder().eplId(id).revision(2.0).title("Book "+id).author("Author").links(String.format("%040X",id)).build());
        books.flush();
        torrent(1,1.0,"DOWNLOADED",tracking.instanceId());torrent(1,2.0,"QUEUED",tracking.instanceId());
        torrent(2,1.0,"DOWNLOADED",tracking.instanceId());torrent(2,3.0,"NOT_FOUND",tracking.instanceId());
        torrent(3,3.0,"DOWNLOADED","foreign-client");torrent(4,1.0,"UNKNOWN",tracking.instanceId());
        torrent(5,3.0,"DOWNLOADED",tracking.instanceId());torrent(6,3.0,"NOT_FOUND",tracking.instanceId());
        calibre(1,1.0,true);calibre(1,2.0,true);calibre(2,1.0,true);calibre(2,3.0,false);
        calibre(3,3.0,false);calibre(4,null,true);calibre(6,3.0,true);
    }
    void torrent(long id,Double revision,String state,String client) {
        String key=java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,?,?, 'qbittorrent',?,?,'EPLSYNC',0)",key,id,revision,key,client,state);
    }
    void calibre(long id,Double revision,boolean active) {
        String key=java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?,?,?, ?,?,?,'Book','Author')",key,key,active,Math.abs(key.hashCode())+1L,id,revision);
    }
    void matches(String key,String value,Integer... ids) throws Exception {
        var result=mvc.perform(get("/api/catalog/books").param(key,value).param("sort","eplId,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(ids.length))
            .andExpect(jsonPath("$.items.length()").value(ids.length));
        if(ids.length>0) result.andExpect(jsonPath("$.items[*].eplId",org.hamcrest.Matchers.contains(ids)));
    }
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    @Autowired com.rlibanez.eplsync.config.TorrentProperties torrentProperties;

    @Test void combinationsIntersectLibrariesStatesAndHighestRevisions() {
        String[] choices={"", "PRESENT", "ABSENT"};
        String[] revisions={"", "UPDATED", "OUTDATED"};
        for(String tp:choices) for(String tr:revisions) for(String cp:choices) for(String cr:revisions)
        for(boolean downloaded:new boolean[]{false,true}) {
            if((tp.equals("ABSENT") && (!tr.isEmpty() || downloaded)) || (cp.equals("ABSENT") && !cr.isEmpty())) continue;
            var f=new CatalogBookFilter();f.setTorrentPresence(tp);f.setTorrentRevision(tr);f.setCalibrePresence(cp);f.setCalibreRevision(cr);
            if(downloaded) f.setTorrentStatus(new com.rlibanez.eplsync.torrent.downloads.DownloadStatus[]{com.rlibanez.eplsync.torrent.downloads.DownloadStatus.DOWNLOADED});
            var expected=new java.util.ArrayList<Long>();
            for(long id=1;id<=6;id++) {
                boolean tor=java.util.Set.of(1L,2L,4L,5L).contains(id),cal=java.util.Set.of(1L,2L,4L,6L).contains(id);
                if(tp.equals("PRESENT") && !tor || tp.equals("ABSENT") && tor) continue;
                if(cp.equals("PRESENT") && !cal || cp.equals("ABSENT") && cal) continue;
                if(tr.equals("UPDATED") && !java.util.Set.of(1L,5L).contains(id) || tr.equals("OUTDATED") && !java.util.Set.of(2L,4L).contains(id)) continue;
                if(cr.equals("UPDATED") && !java.util.Set.of(1L,6L).contains(id) || cr.equals("OUTDATED") && id!=2) continue;
                if(downloaded && !java.util.Set.of(1L,2L,5L).contains(id)) continue;
                expected.add(id);
            }
            var result=service.search(f,org.springframework.data.domain.PageRequest.of(0,20,org.springframework.data.domain.Sort.by("eplId")));
            assertThat(result.getContent()).as("torrent=%s/%s downloaded=%s calibre=%s/%s",tp,tr,downloaded,cp,cr)
                .extracting(CatalogBook::getEplId).containsExactlyElementsOf(expected);
            assertThat(result.getTotalElements()).isEqualTo(expected.size());
        }
    }
    @Test void selectAllBulkPreviewAndExportRespectCombinedFiltersAndExclusions() throws Exception {
        var f=tools.jackson.databind.json.JsonMapper.builder().build().readValue("{\"torrentPresence\":\"PRESENT\",\"torrentRevision\":\"UPDATED\",\"torrentStatus\":[\"DOWNLOADED\",\"QUEUED\"],\"calibrePresence\":\"PRESENT\",\"calibreRevision\":\"UPDATED\",\"excludedIds\":[]}",CatalogBookFilter.class);
        torrentProperties.effective().setEnabled(true);
        try {
            var preview=bulk.preview(f,org.springframework.data.domain.PageRequest.of(0,1),false,true,null,true);
            assertThat(preview.selectedBooks()).isEqualTo(1);
            assertThat(preview.items()).extracting(com.rlibanez.eplsync.torrent.bulk.BulkStore.ItemView::eplId).containsExactly(1L);
            f.setExcludedIds(new Long[]{1L});
            assertThat(bulk.preview(f,org.springframework.data.domain.PageRequest.of(0,1),false,true,null,true).selectedBooks()).isZero();
            try(var file=exports.prepare(f,org.springframework.data.domain.Sort.by("eplId"))) {
                var output=new java.io.ByteArrayOutputStream();file.writeTo(output);assertThat(output.size()).isZero();
            }
        } finally { torrentProperties.effective().setEnabled(false); }
    }
    @Test void torrentStatesMatchAnySelectedStateInCurrentClient() throws Exception {
        matches("torrentStatus","QUEUED",1);
        matches("torrentStatus","DOWNLOADED",1,2,5);
        mvc.perform(get("/api/catalog/books").param("torrentStatus","QUEUED","UNKNOWN").param("sort","eplId,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].eplId",org.hamcrest.Matchers.contains(1,4)));
        mvc.perform(get("/api/catalog/books").param("torrentStatus","DOWNLOADED").param("torrentRevision","OUTDATED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eplId").value(2));
        mvc.perform(get("/api/catalog/books").param("torrentStatus","DOWNLOADED").param("torrentPresence","ABSENT"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books").param("torrentStatus", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(6));
    }
    @Test void invalidTorrentStatesAreRejected() throws Exception {
        for(String state:new String[]{"NOT_FOUND","invalid"}) mvc.perform(get("/api/catalog/books").param("torrentStatus",state))
            .andExpect(status().isBadRequest());
    }
    @Test void torrentPresenceUsesCurrentClientAndExcludesNotFound() throws Exception {
        matches("torrentPresence","PRESENT",1,2,4,5);matches("torrentPresence","ABSENT",3,6);
    }
    @Test void calibrePresenceUsesOnlyActiveRowsAndDoesNotDuplicateCatalogBooks() throws Exception {
        matches("calibrePresence","PRESENT",1,2,4,6);matches("calibrePresence","ABSENT",3,5);
    }
    @Test void revisionUsesHighestKnownRevisionIncludingNewerCopiesAndExcludesUnknowns() throws Exception {
        matches("torrentRevision","UPDATED",1,5);matches("torrentRevision","OUTDATED",2,4);
        matches("calibreRevision","UPDATED",1,6);matches("calibreRevision","OUTDATED",2);
    }
    @Test void combinesLibrariesAndExistingCriteriaAndPaginatesCorrectly() throws Exception {
        mvc.perform(get("/api/catalog/books").param("torrentPresence","PRESENT").param("calibrePresence","ABSENT").param("title","Book"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].eplId").value(5));
        mvc.perform(get("/api/catalog/books").param("torrentPresence","PRESENT").param("size","1").param("page","1").param("sort","eplId,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(4)).andExpect(jsonPath("$.items[0].eplId").value(2));
    }
    @Test void emptyFieldsDoNotFilterAndContradictoryOrInvalidValuesAreRejected() throws Exception {
        for(String library:new String[]{"torrent","calibre"}) {
            mvc.perform(get("/api/catalog/books").param(library+"Presence","").param(library+"Revision",""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(6));
            mvc.perform(get("/api/catalog/books").param(library+"Presence","ABSENT").param(library+"Revision","UPDATED")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/catalog/books").param(library+"Presence","all")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/catalog/books").param(library+"Revision","current")).andExpect(status().isBadRequest());
        }
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"CATALOG_READ"})
    void cannotInferLibraryContentsWithoutReadPermission() throws Exception {
        mvc.perform(get("/api/catalog/books").param("torrentPresence","ABSENT")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/books").param("torrentStatus","DOWNLOADED")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/books").param("calibrePresence","PRESENT")).andExpect(status().isForbidden());
    }
    @Test @org.springframework.security.test.context.support.WithMockUser(authorities={"CATALOG_READ","TORRENT_SYNC"})
    void torrentSyncReadPermissionAlsoAllowsLocalTorrentFilters() throws Exception {
        matches("torrentPresence","PRESENT",1,2,4,5);
    }
    @Test void discardedCalibreSnapshotDoesNotLeakPreviousLibraryCopies() throws Exception {
        jdbc.update("UPDATE calibre_books SET active=false");
        matches("calibrePresence","ABSENT",1,2,3,4,5,6);
        matches("calibreRevision","UPDATED");
    }
    @Test void magnetSearchAndExportUseTheSameLibraryScope() throws Exception {
        mvc.perform(get("/api/catalog/magnets").param("torrentPresence","PRESENT").param("calibrePresence","ABSENT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items.length()").value(1));
        var filter=new CatalogBookFilter();filter.setTorrentPresence("PRESENT");filter.setCalibrePresence("ABSENT");
        try(var file=exports.prepare(filter,org.springframework.data.domain.Sort.by("eplId"))) {
            var output=new java.io.ByteArrayOutputStream();file.writeTo(output);
            assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList()).hasSize(1).allSatisfy(line->assertThat(line).contains(String.format("%040X",5)));
        }
    }
    @Test void jsonTorrentStatesRetainMultipleAndSingleValuesForSelections() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var filter=mapper.readValue("{\"torrentStatus\":[\"QUEUED\",\"UNKNOWN\"]}",CatalogBookFilter.class);
        assertThat(service.search(filter,org.springframework.data.domain.PageRequest.of(0,20,org.springframework.data.domain.Sort.by("eplId"))).getContent()).extracting(CatalogBook::getEplId).containsExactly(1L,4L);
        var single=mapper.readValue("{\"torrentStatus\":\"QUEUED\"}",CatalogBookFilter.class);
        assertThat(service.search(single,org.springframework.data.domain.PageRequest.of(0,20)).getContent()).extracting(CatalogBook::getEplId).containsExactly(1L);
    }
    @Test void jsonFiltersRetainTheSameCriteriaForSelectAllOperations() {
        var filter=tools.jackson.databind.json.JsonMapper.builder().build().readValue("{\"torrentPresence\":\"PRESENT\",\"calibrePresence\":\"ABSENT\"}",CatalogBookFilter.class);
        assertThat(service.search(filter,org.springframework.data.domain.PageRequest.of(0,20)).getContent()).extracting(CatalogBook::getEplId).containsExactly(5L);
    }
}
