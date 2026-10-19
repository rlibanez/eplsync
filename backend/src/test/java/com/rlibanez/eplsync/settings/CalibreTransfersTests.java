package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.events.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(authorities={"CALIBRE_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","CALIBRE_JOBS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class CalibreTransfersTests {
    @Autowired CalibreTransfers transfers;
    @Autowired WorkListings listings;
    @Autowired CalibreSettings settings;
    @Autowired CalibreCatalogService catalog;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @MockitoSpyBean AccountStore accounts;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    @MockitoSpyBean com.rlibanez.eplsync.service.TorrentClientService torrent;
    com.rlibanez.eplsync.torrent.TorrentClient adapter;

    Account owner;String record;byte[] bytes;MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM scheduled_tasks");jdbc.update("DELETE FROM calibre_jobs");jdbc.update("DELETE FROM calibre_books");jdbc.update("DELETE FROM app_settings WHERE setting_key IN ('calibre.connection','calibre.snapshot','calibre.identifiers')");downloads.deleteAll();accounts.clear();
        owner=accounts.initialize("Owner","owner@example.com","password123","password123");
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        var row=new DownloadRecord();row.setEplId(1L);row.setRevision(1.1);row.setHash("A".repeat(40));row.setClient("qbittorrent");row.setClientInstanceId(tracking.instanceId());row.setStatus(DownloadStatus.DOWNLOADED);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());record=downloads.save(row).getId();
        adapter=mock(com.rlibanez.eplsync.torrent.TorrentClient.class);
        when(adapter.listTorrents(anySet())).thenAnswer(call -> downloads.findAll().stream()
            .map(download -> new RemoteTorrent(download.getHash(),download.getStatus(),null)).toList());
        doAnswer(call -> call.<java.util.function.Function<com.rlibanez.eplsync.torrent.TorrentClient,Object>>getArgument(0).apply(adapter)).when(torrent).exclusiveClient(any());
        bytes=epub(1,"1.10");
        when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main"),new CalibreClient.Library("other","Other")),"main"));
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(files.download(record,0)).thenAnswer(call -> new EpubFiles.Opened("book.epub",bytes.length,new java.io.ByteArrayInputStream(bytes)));
        when(client.findRevision(any(),anyLong(),any())).thenReturn(null);
        when(client.hasEpub(any(),anyLong())).thenReturn(true);
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenReturn(9L);
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Autowired CatalogCalibreTransfers catalogTransfers;
    @Autowired com.rlibanez.eplsync.repository.CatalogBookRepository catalogBooks;
    @Autowired com.rlibanez.eplsync.config.TorrentProperties torrentProperties;
    void seedCatalog() {
        catalogBooks.deleteAllInBatch();
        for(long id=1;id<=3;id++) catalogBooks.save(com.rlibanez.eplsync.model.CatalogBook.builder().eplId(id).title("Book "+id).author("Author").revision(1.1).links(String.format("%040X",id)).build());
        catalogBooks.flush();
    }
    CatalogCalibreTransfers.Prepared catalogSelection(Long... ids) {
        var filter=new com.rlibanez.eplsync.filter.CatalogBookFilter();filter.setSelectedIds(ids);
        return catalogTransfers.prepare(new CatalogCalibreTransfers.Selection(filter));
    }
    CatalogCalibreTransfers.Request catalogRequest(CatalogCalibreTransfers.Prepared prepared,boolean download) {
        return new CatalogCalibreTransfers.Request(UUID.randomUUID().toString(),"other",prepared.books(),prepared.files().stream().map(f->new CalibreTransfers.FileChoice(f.recordId(),f.index())).toList(),download,prepared.missing().stream().map(book -> Objects.requireNonNull(book).eplId()).toList());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SYNC"})
    void catalogPrepareUsesFiltersAndExclusionsAndPinsOnlyTheCatalogRevision() {
        seedCatalog();var excluded=new com.rlibanez.eplsync.filter.CatalogBookFilter();excluded.setAuthor("Author");excluded.setExcludedIds(new Long[]{1L});
        assertThat(catalogTransfers.prepare(new CatalogCalibreTransfers.Selection(excluded)).books()).extracting(book -> Objects.requireNonNull(book).eplId()).containsExactly(2L,3L);
        var filter=new com.rlibanez.eplsync.filter.CatalogBookFilter();filter.setTorrentPresence("PRESENT");filter.setTorrentRevision("UPDATED");filter.setTorrentStatus(new DownloadStatus[]{DownloadStatus.DOWNLOADED,DownloadStatus.QUEUED});filter.setExcludedIds(new Long[]{2L});
        var prepared=catalogTransfers.prepare(new CatalogCalibreTransfers.Selection(filter));
        assertThat(prepared.books()).extracting(book -> Objects.requireNonNull(book).eplId()).containsExactly(1L);
        assertThat(prepared.files()).extracting(candidate -> Objects.requireNonNull(candidate).recordId()).containsExactly(record);
        jdbc.update("UPDATE catalog_books SET revision=2 WHERE epl_id=1");
        assertThat(catalogSelection(1L).missing()).extracting(book -> Objects.requireNonNull(book).eplId()).containsExactly(1L);
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ"})
    void catalogSendReusesTransferWorkerLibrarySelectionAndIdempotency() {
        seedCatalog();var request=catalogRequest(catalogSelection(1L),false);
        var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
        var job=EventContext.withActor(actor,()->catalogTransfers.create(request));
        assertThat(job.library()).isEqualTo("other");
        assertThat(EventContext.withActor(actor,()->catalogTransfers.create(request)).id()).isEqualTo(job.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);
        transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client).uploadEpub(argThat(c->c.library().equals("other")),any(),anyString(),anyString());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ"})
    void catalogRejectsStaleRevisionAndFilesOutsideSelection() {
        seedCatalog();var prepared=catalogSelection(1L);var request=catalogRequest(prepared,false);
        jdbc.update("UPDATE catalog_books SET revision=2 WHERE epl_id=1");
        assertThatThrownBy(()->catalogTransfers.create(request)).hasMessageContaining("catálogo cambió");
        var other=catalogSelection(2L);
        assertThatThrownBy(()->catalogTransfers.create(new CatalogCalibreTransfers.Request(UUID.randomUUID().toString(),"main",other.books(),List.of(new CalibreTransfers.FileChoice(record,0)),false,List.of()))).hasMessageContaining("EPUB seleccionado cambió");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isZero();
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ"})
    void catalogMissingFilesRequireExplicitOptInAndTorrentPermissions() {
        seedCatalog();var prepared=catalogSelection(2L);
        assertThatThrownBy(()->catalogTransfers.create(catalogRequest(prepared,false))).hasMessageContaining("activa la descarga");
        assertThatThrownBy(()->catalogTransfers.create(catalogRequest(prepared,true))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC"})
    void catalogMissingFilesCreateLinkedJobsAndReusePendingDownload() {
        seedCatalog();torrentProperties.effective().setEnabled(true);
        try {
            var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
            var prepared=catalogSelection(2L);
            var first=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(prepared,true)));
            var dependency=transfers.items(first.id(),0,20,List.of("position,asc")).items().getFirst();
            assertThat(dependency.state()).isEqualTo("WAITING_DOWNLOAD");assertThat(dependency.downloadJobId()).isNotBlank();
            var second=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(prepared,true)));
            assertThat(transfers.items(second.id(),0,20,List.of("position,asc")).items().getFirst().downloadJobId()).isEqualTo(dependency.downloadJobId());
        } finally {torrentProperties.effective().setEnabled(false);}
    }
    String completedCatalogDownload(long id,String hash) throws Exception {
        var row=new DownloadRecord();row.setEplId(id);row.setRevision(1.1);row.setHash(hash);row.setClient("qbittorrent");row.setClientInstanceId(tracking.instanceId());row.setStatus(DownloadStatus.DOWNLOADED);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());
        String recordId=downloads.save(row).getId();byte[] content=epub(id,"1.1");
        when(files.list(recordId)).thenReturn(List.of(new EpubFiles.Item(0,"ready.epub",content.length)));
        when(files.download(recordId,0)).thenAnswer(call->new EpubFiles.Opened("ready.epub",content.length,new java.io.ByteArrayInputStream(content)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book "+id,List.of("Author"),id,new BigDecimal("1.1"),true));
        return recordId;
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ"})
    void catalogUsesDownloadCompletedWhileAssistantWasOpenWithoutSelectingUncheckedFiles() throws Exception {
        seedCatalog();var prepared=catalogSelection(1L,2L);
        var request=new CatalogCalibreTransfers.Request(UUID.randomUUID().toString(),"main",prepared.books(),List.of(),true,List.of(2L));
        String ready=completedCatalogDownload(2,"B".repeat(40));
        var job=EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->catalogTransfers.create(request));
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items()).singleElement().satisfies(item->{
            assertThat(item.eplId()).isEqualTo(2L);assertThat(item.recordId()).isEqualTo(ready);assertThat(item.downloadJobId()).isNull();assertThat(item.state()).isEqualTo("PENDING");
        });
        transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC"})
    void catalogDeferredDownloadsFinishAndSecondJobSkipsVerifiedEpub() throws Exception {
        seedCatalog();torrentProperties.effective().setEnabled(true);
        try {
            var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
            var firstRequest=catalogRequest(catalogSelection(2L),true);
            var first=EventContext.withActor(actor,()->catalogTransfers.create(firstRequest));
            var second=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(catalogSelection(2L),true)));
            jdbc.update("UPDATE calibre_jobs SET next_probe=?",Long.MAX_VALUE); // isolate remote transport; the durable dependency is real
            jdbc.update("UPDATE torrent_bulk_items SET state='ACCEPTED' WHERE epl_id=2");
            String hash=jdbc.queryForObject("SELECT hash FROM torrent_bulk_items WHERE epl_id=2 LIMIT 1",String.class);
            completedCatalogDownload(2,hash);
            when(client.findRevision(any(),eq(2L),any())).thenReturn(null,9L);
            for(int n=0;n<8;n++) transfers.cycle();
            assertThat(transfers.job(first.id()).accepted()).isEqualTo(1);
            assertThat(transfers.job(second.id()).skipped()).isEqualTo(1);
            assertThat(transfers.job(first.id()).state()).isEqualTo("COMPLETED");assertThat(transfers.job(second.id()).state()).isEqualTo("COMPLETED");
            verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
            assertThat(EventContext.withActor(actor,()->catalogTransfers.create(firstRequest)).id()).isEqualTo(first.id());
        } finally {torrentProperties.effective().setEnabled(false);}
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC"})
    void catalogRedownloadsDeletedBooksInsteadOfReusingCompletedHistory() throws Exception {
        seedCatalog();torrentProperties.effective().setEnabled(true);
        try {
            var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
            var first=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(catalogSelection(2L),true)));
            String original=transfers.items(first.id(),0,20,List.of("position,asc")).items().getFirst().downloadJobId();
            jdbc.update("UPDATE calibre_jobs SET next_probe=?",Long.MAX_VALUE);
            jdbc.update("UPDATE torrent_bulk_items SET state='ACCEPTED' WHERE job_id=?",original);
            jdbc.update("UPDATE torrent_bulk_jobs SET state='COMPLETED' WHERE id=?",original);
            String hash=jdbc.queryForObject("SELECT hash FROM torrent_bulk_items WHERE job_id=?",String.class,original);
            String record=completedCatalogDownload(2,hash);
            transfers.cycle();transfers.cycle();
            assertThat(transfers.job(first.id()).accepted()).isEqualTo(1);
            // Both external copies and the local download record were deleted; history remains.
            downloads.deleteById(record);downloads.flush();
            var second=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(catalogSelection(2L,3L),true)));
            var rows=transfers.items(second.id(),0,20,List.of("position,asc")).items();
            assertThat(rows).hasSize(2);
            String replacement=rows.getFirst().downloadJobId();
            assertThat(replacement).isNotEqualTo(original);
            assertThat(rows).allSatisfy(item -> {assertThat(item.downloadJobId()).isEqualTo(replacement);assertThat(item.state()).isEqualTo("WAITING_DOWNLOAD");});
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE job_id=?",Integer.class,replacement)).isEqualTo(2);
            // Repeating the request while the new sends are pending must reuse them.
            var third=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(catalogSelection(2L,3L),true)));
            assertThat(transfers.items(third.id(),0,20,List.of("position,asc")).items()).allSatisfy(item -> assertThat(item.downloadJobId()).isEqualTo(replacement));
        } finally {torrentProperties.effective().setEnabled(false);}
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC"})
    void catalogRechecksCachedCompletedRecordAndDoesNotTreatNetworkFailureAsAbsence() throws Exception {
        seedCatalog();torrentProperties.effective().setEnabled(true);
        try {
            String record=completedCatalogDownload(2,"D".repeat(40));
            int count=jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class);
            when(adapter.listTorrents(anySet())).thenThrow(new IllegalStateException("remote unavailable"));
            assertThatThrownBy(() -> catalogSelection(2L)).hasMessage("remote unavailable");
            assertThat(downloads.findById(record).orElseThrow().getStatus()).isEqualTo(DownloadStatus.DOWNLOADED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(count);
            doReturn(List.of()).when(adapter).listTorrents(anySet());
            var prepared=catalogSelection(2L);
            assertThat(prepared.files()).isEmpty();
            assertThat(prepared.missing()).singleElement().satisfies(book -> assertThat(book.eplId()).isEqualTo(2));
            var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
            var job=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(prepared,true)));
            assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items()).singleElement().satisfies(item -> {
                assertThat(item.state()).isEqualTo("WAITING_DOWNLOAD");assertThat(item.downloadJobId()).isNotBlank();
            });
        } finally {torrentProperties.effective().setEnabled(false);}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC"})
    void catalogChecksRemotePresenceBeforeReusingAcceptedDownload(boolean present) throws Exception {
        seedCatalog();torrentProperties.effective().setEnabled(true);
        try {
            var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
            var first=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(catalogSelection(2L),true)));
            String original=transfers.items(first.id(),0,20,List.of("position,asc")).items().getFirst().downloadJobId();
            int previousJobs=jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class);
            jdbc.update("UPDATE torrent_bulk_items SET state='ACCEPTED' WHERE job_id=?",original);
            jdbc.update("UPDATE torrent_bulk_jobs SET state='COMPLETED' WHERE id=?",original);
            String hash=jdbc.queryForObject("SELECT hash FROM torrent_bulk_items WHERE job_id=?",String.class,original);
            String record=completedCatalogDownload(2,hash);
            jdbc.update("UPDATE torrent_downloads SET status='DOWNLOADING' WHERE id=?",record);
            var request=catalogRequest(catalogSelection(2L),true);
            when(adapter.listTorrents(anySet())).thenReturn(present?List.of(new RemoteTorrent(hash,DownloadStatus.DOWNLOADING,null)):List.of());
            var second=EventContext.withActor(actor,()->catalogTransfers.create(request));
            var item=transfers.items(second.id(),0,20,List.of("position,asc")).items().getFirst();
            assertThat(item.state()).isEqualTo("WAITING_DOWNLOAD");
            if(present) assertThat(item.downloadJobId()).isEqualTo(original);
            else {
                assertThat(item.downloadJobId()).isNotEqualTo(original);
                assertThat(downloads.findById(record).orElseThrow().getStatus()).isEqualTo(DownloadStatus.NOT_FOUND);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(previousJobs+(present?0:1));
            assertThat(EventContext.withActor(actor,()->catalogTransfers.create(request)).id()).isEqualTo(second.id());
            var another=EventContext.withActor(actor,()->catalogTransfers.create(catalogRequest(catalogSelection(2L),true)));
            assertThat(transfers.items(another.id(),0,20,List.of("position,asc")).items()).singleElement().satisfies(next -> assertThat(next.downloadJobId()).isEqualTo(item.downloadJobId()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(previousJobs+(present?0:1));
            verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        } finally {torrentProperties.effective().setEnabled(false);}
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC"})
    void catalogFailedDownloadPausesWithoutUploadingOrUsingAnotherRevision() throws Exception {
        seedCatalog();torrentProperties.effective().setEnabled(true);
        try {
            var job=EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),()->catalogTransfers.create(catalogRequest(catalogSelection(2L),true)));
            jdbc.update("UPDATE calibre_jobs SET next_probe=?",Long.MAX_VALUE);
            jdbc.update("UPDATE torrent_bulk_items SET state='FAILED' WHERE epl_id=2");
            completedCatalogDownload(2,"C".repeat(40)); // unrelated hash must not fulfill the pinned dependency
            transfers.cycle();
            assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
            assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("BLOCKED_DOWNLOAD");
            verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        } finally {torrentProperties.effective().setEnabled(false);}
    }
    @Test @WithMockUser(authorities={"CALIBRE_SEND","DOWNLOADS_FILES_READ"})
    void catalogEndpointRequiresCatalogPermission() throws Exception {
        mvc.perform(post("/api/calibre/transfers/catalog/prepare").with(csrf()).contentType("application/json").content("{\"filters\":{\"selectedIds\":[1]}}"))
            .andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ"})
    void jobItemsUseCurrentCatalogCoverWithoutChangingTransferSnapshot() {
        seedCatalog();jdbc.update("UPDATE catalog_books SET cover_url=?,cover_available=true WHERE epl_id=1","https://example.org/custom-cover.jpg");
        var job=create();var item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst();
        assertThat(item.position()).isZero();
        assertThat(item.coverUrl()).isEqualTo("https://example.org/custom-cover.jpg");assertThat(item.coverAvailable()).isTrue();assertThat(item.name()).isEqualTo("book.epub");
        jdbc.update("UPDATE catalog_books SET cover_url=? WHERE epl_id=1","https://example.org/replaced-cover.jpg");
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().coverUrl()).isEqualTo("https://example.org/replaced-cover.jpg");
        catalogBooks.deleteAllInBatch();
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().coverUrl()).isNull();
    }
    @Test @WithMockUser(authorities={"CALIBRE_SEND","DOWNLOADS_FILES_READ","CATALOG_READ"})
    void authorFiltersCombineWithFilesStatesAndPaginationAndPreserveMissingCatalogEntries() throws Exception {
        var job=create();catalogBooks.deleteAllInBatch();
        for(int n=1;n<=3;n++) jdbc.update("INSERT INTO catalog_books(epl_id,title,author,revision) VALUES(?,'Book',?,1)",n,n==1?"Zeta Writer":n==2?"Alpha Writer":"Beta Writer");
        for(int n=2;n<=4;n++) jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state) VALUES(?,?,?,'record',?, ?,20,?,'1','FAILED')","author-"+n,job.id(),n,n,"file-"+n+".epub",n);
        var filters=new org.springframework.util.LinkedMultiValueMap<String,String>();filters.add("author","alpha");filters.add("author","beta");
        var result=transfers.items(job.id(),1,1,"","",List.of("author,asc"),"eq",filters);
        var initialAuthentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items").param("author","alpha","beta").param("sort","author,asc").param("page","1").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2)).andExpect(jsonPath("$.items[0].author").value("Beta Writer"));
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items").param("author","alpha","beta").param("authorOperator","ne").param("state","FAILED").param("sort","position,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].eplId").value(4));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(initialAuthentication);

        assertThat(result.meta().totalItems()).isEqualTo(2);
        assertThat(result.items()).singleElement().satisfies(item->{assertThat(item.eplId()).isEqualTo(3);assertThat(item.author()).isEqualTo("Beta Writer");});
        filters.add("file","file-3");
        assertThat(transfers.items(job.id(),0,20,"FAILED","",List.of("author,asc"),"eq",filters).items()).singleElement().satisfies(item->assertThat(item.eplId()).isEqualTo(3));
        filters.remove("file");filters.set("authorOperator","ne");
        var excluded=transfers.items(job.id(),0,20,"","",List.of("eplId,asc"),"eq",filters);
        assertThat(excluded.items()).extracting(item->item.eplId()).containsExactly(1L,4L);
        assertThat(excluded.items().getLast().author()).isNull();
        filters.clear();filters.set("author","%' OR 1=1 --");
        assertThat(transfers.items(job.id(),0,20,"","",List.of("author,asc"),"eq",filters).items()).isEmpty();
    }
    @Test void authorCannotBeReadFilteredOrSortedWithoutCatalogPermission() throws Exception {
        var job=create();
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().author()).isNull();
        var filters=new org.springframework.util.LinkedMultiValueMap<String,String>();filters.set("author","Writer");
        assertThatThrownBy(()->transfers.items(job.id(),0,20,"","",List.of("position,asc"),"eq",filters)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->transfers.items(job.id(),0,20,List.of("author,asc"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void itemFiltersApplyMultipleIdsFileTermsAndStatesBeforePagination() throws Exception {
        var job=create();
        for(int n=2;n<=4;n++) jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message) VALUES(?,?,?,'record',?, ?,20,?,'1',?,?)","filter-"+n,job.id(),n,n,n==2?"Another,Title.epub":"file-"+n+".epub",n,n==2?"ACCEPTED":"FAILED","detail");
        var filters=new org.springframework.util.LinkedMultiValueMap<String,String>();
        filters.add("eplId","1");filters.add("eplId","3");filters.add("file","book");filters.add("file","file-3");
        var page=transfers.items(job.id(),1,1,"PENDING,FAILED","",List.of("position,asc"),"eq",filters);
        assertThat(page.meta().totalItems()).isEqualTo(2);assertThat(page.items()).singleElement().satisfies(item -> assertThat(item.eplId()).isEqualTo(3));
        filters.clear();filters.add("file","Another,Title");
        assertThat(transfers.items(job.id(),0,20,"","",List.of("position,asc"),"eq",filters).items()).singleElement().satisfies(item -> assertThat(item.eplId()).isEqualTo(2));
        filters.set("fileOperator","ne");
        assertThat(transfers.items(job.id(),0,20,"PENDING,ACCEPTED","",List.of("position,asc"),"ne",filters).items()).hasSize(2);
        filters.clear();filters.add("eplId","1");filters.add("eplId","3");filters.set("eplIdOperator","ne");
        assertThat(transfers.items(job.id(),0,20,"FAILED","",List.of("position,asc"),"eq",filters).items()).singleElement().satisfies(item -> assertThat(item.eplId()).isEqualTo(4));
        filters.clear();filters.add("file","%' OR 1=1 --");
        assertThat(transfers.items(job.id(),0,20,"","",List.of("position,asc"),"eq",filters).items()).isEmpty();
        filters.clear();for(long n=1;n<=1200;n++) filters.add("eplId",Long.toString(n));
        assertThat(transfers.items(job.id(),0,20,"","",List.of("position,asc"),"eq",filters).items()).hasSize(4);
        filters.set("eplId","-1");
        assertThatThrownBy(()->transfers.items(job.id(),0,20,"","",List.of("position,asc"),"eq",filters)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items").param("state","PENDING,FAILED").param("eplId","1","3").param("file","book","file-3").param("size","1").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2)).andExpect(jsonPath("$.items[0].eplId").value(3));
    }
    @Test void jobsFilterByUsernameAndState() {
        String now=Instant.now().toString();
        for(String name:List.of("Roberto","Otra cuenta")) jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),now,now,owner.id(),name,"destination","main","source",name.equals("Roberto") ? "COMPLETED" : "QUEUED");
        assertThat(transfers.jobs(0,20,"COMPLETED"," rober ",List.of("id,asc")).items()).extracting(job -> job.username()).containsExactly("Roberto");
        assertThat(transfers.jobs(0,20,"QUEUED","Roberto",List.of("createdAt,desc")).meta().totalItems()).isZero();
    }
    @Test void jobsFilterByCreationDates() {
        String id=UUID.randomUUID().toString(),created="2026-10-13T12:00:00Z";
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_id,owner_username,destination,library,source,state) VALUES(?,?,?,?,?,?,?,?,?)",id,created,created,owner.id(),"Owner","destination","main","source","COMPLETED");
        assertThat(transfers.jobs(0,20,"","",Instant.parse(created),Instant.parse("2026-10-14T00:00:00Z"),List.of("createdAt,desc")).meta().totalItems()).isEqualTo(1);
        assertThat(transfers.jobs(0,20,"","",null,Instant.parse(created),List.of("createdAt,desc")).meta().totalItems()).isZero();
        assertThatThrownBy(() -> transfers.jobs(0,20,"","",Instant.parse(created),Instant.parse(created),List.of("createdAt,desc"))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    static byte[] epub(long epl,String revision) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(output)) {
            for(var entry:Map.of("mimetype","application/epub+zip","META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>","content.opf","<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:identifier>epl:"+epl+"</dc:identifier><dc:identifier>revision:"+revision+"</dc:identifier></metadata></package>").entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        } return output.toByteArray();
    }
    static byte[] withoutIdentifiers(boolean missingId,boolean missingRevision) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        try(var input=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(epub(1,"1.10")));var zip=new java.util.zip.ZipOutputStream(output)) {
            java.util.zip.ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                byte[] content=input.readAllBytes();
                if(entry.getName().equals("content.opf")) {
                    String xml=new String(content,java.nio.charset.StandardCharsets.UTF_8);
                    if(missingId) xml=xml.replace("<dc:identifier>epl:1</dc:identifier>","");
                    if(missingRevision) xml=xml.replace("<dc:identifier>revision:1.10</dc:identifier>","");
                    content=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(content);zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,false,EPUB enviado sin EPL ID","false,true,EPUB enviado sin revisión","true,true,EPUB enviado sin EPL ID ni revisión"})
    void sendsWithoutOptionalIdentifiers(boolean missingId,boolean missingRevision,String message) throws Exception {
        bytes=withoutIdentifiers(missingId,missingRevision);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Libro",List.of("Autor"),missingId?null:1L,missingRevision?null:new java.math.BigDecimal("1.1"),false,"book-uuid"));
        when(client.completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"))).thenReturn(new CalibreClient.Book(9,"Libro",List.of("Autor"),1L,new BigDecimal("1.1"),true,"book-uuid"));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("Identificadores ausentes completados");
        assertThat(jdbc.queryForObject("SELECT identifiers_completed FROM calibre_job_items WHERE job_id=?",Boolean.class,job.id())).isTrue();
        verify(client).findRevision(any(),eq(1L),any());
        verify(client).uploadEpub(any(),any(),anyString(),anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,false", "false,true", "true,true"})
    void disabledAutomaticIdentifiersStillSendsAndVerifiesWithoutChangingCalibre(boolean missingId,boolean missingRevision) throws Exception {
        settings.saveIdentifierOptions(new CalibreSettings.IdentifierOptions(false));
        bytes=withoutIdentifiers(missingId,missingRevision);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Libro",List.of("Autor"),missingId?null:1L,missingRevision?null:new BigDecimal("1.1"),false,"book-uuid"));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).doesNotContain("Identificadores ausentes completados");
        assertThat(jdbc.queryForObject("SELECT identifiers_completed FROM calibre_job_items WHERE job_id=?",Boolean.class,job.id())).isFalse();
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),anyString());
        verify(client).hasEpub(any(),eq(9L));
        verify(client).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void turningAutomaticIdentifiersBackOnAppliesToTheNextSend() throws Exception {
        settings.saveIdentifierOptions(new CalibreSettings.IdentifierOptions(false));
        bytes=withoutIdentifiers(true,true);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Libro",List.of("Autor"),null,null,false,"book-uuid"));
        when(client.completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"))).thenReturn(new CalibreClient.Book(9,"Libro",List.of("Autor"),1L,new BigDecimal("1.1"),true,"book-uuid"));
        var job=create();
        settings.saveIdentifierOptions(new CalibreSettings.IdentifierOptions(true));
        transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client).completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"));
    }
    @Test void completedIdentifiersLetANewRequestSkipTheSameRevisionWithoutUploadingAgain() throws Exception {
        bytes=withoutIdentifiers(true,true);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).skipped()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void metadataTimeoutAndRestartVerifyTheSameEntryWithoutImportingAgain() throws Exception {
        bytes=withoutIdentifiers(true,true);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),null,null,false,"book-uuid"));
        when(client.completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid")))
            .thenThrow(new CalibreClient.TransientFailure(org.springframework.http.HttpStatus.BAD_GATEWAY,"Respuesta perdida",null));
        var job=create();transfers.cycle();
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT identifier_completion_pending FROM calibre_job_items WHERE job_id=?",Boolean.class,job.id())).isTrue();
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true,"book-uuid"));
        jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE id=?",job.id());jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE job_id=?",job.id());
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT identifiers_completed FROM calibre_job_items WHERE job_id=?",Boolean.class,job.id())).isTrue();
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,times(1)).completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void disablingIdentifiersDuringUncertainRecoveryDoesNotRepeatTheMetadataWrite(boolean alreadyCompleted) throws Exception {
        bytes=withoutIdentifiers(true,true);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),null,null,false,"book-uuid"));
        when(client.completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"))).thenThrow(new CalibreClient.TransientFailure(org.springframework.http.HttpStatus.BAD_GATEWAY,"Respuesta perdida",null));
        var job=create();transfers.cycle();
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("UNCERTAIN");
        settings.saveIdentifierOptions(new CalibreSettings.IdentifierOptions(false));
        if(alreadyCompleted) when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true,"book-uuid"));
        jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE id=?",job.id());jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE job_id=?",job.id());
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT identifiers_completed FROM calibre_job_items WHERE job_id=?",Boolean.class,job.id())).isEqualTo(alreadyCompleted);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,times(1)).completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"));
    }
    @Test void emptyWorkerDoesNotPollOrWriteAndCommittedJobWakesIt() throws Exception {
        transfers.cycle();
        clearInvocations(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(transfers,"enabled",true);
        try {
            for(int n=0;n<10;n++) transfers.schedule();
            verifyNoInteractions(jdbc);
            var job=create();
            transfers.schedule();
            var busy=(java.util.concurrent.atomic.AtomicBoolean)org.springframework.test.util.ReflectionTestUtils.getField(transfers,"busy");
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
            while(busy.get() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(busy.get()).isFalse();
            assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
            verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(transfers,"enabled",false);}
    }
    CalibreTransfers.Job create() {
        return EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),() -> transfers.create(new CalibreTransfers.Request("main",List.of(new CalibreTransfers.FileChoice(record,0)))));
    }
    @Test void jobListingAndNeighborsRespectExcludedStates() {
        var job=create();
        assertThat(listings.list("calibre",0,1,List.of("QUEUED"),"","",null,null,"createdAt,desc","ne").items()).isEmpty();
        jdbc.update("UPDATE calibre_jobs SET state='PAUSED' WHERE id=?",job.id());
        assertThat(listings.list("calibre",0,20,List.of(),"SEND","",null,null,"createdAt,desc","eq","ne").items()).isEmpty();
        var result=listings.list("calibre",0,1,List.of("QUEUED"),"","",null,null,"createdAt,desc","ne");
        assertThat(result.meta().totalItems()).isEqualTo(1);assertThat(result.items().getFirst()).containsEntry("id",job.id());
        assertThat(listings.neighbors("calibre",job.id(),List.of("PAUSED"),"","",null,null,"createdAt,desc","ne")).isEqualTo(new WorkListings.Neighbors(null,null));
    }
    @Test void detailFilterCombinesWithStateBeforePagination() throws Exception {
        var job=create();
        jdbc.update("UPDATE calibre_job_items SET state='ACCEPTED',message='EPUB enviado y verificado' WHERE job_id=?",job.id());
        for(int i=1;i<=3;i++) jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,message) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),job.id(),i,record,i,"book.epub",100,1,"1.1",i==3?"FAILED":"ACCEPTED",i==2?"Esperando 100%_ descarga":"EPUB enviado y verificado");
        var excluded=transfers.items(job.id(),0,1,"ACCEPTED","verificado",List.of("position,asc"),"ne");
        assertThat(excluded.meta().totalItems()).isEqualTo(1);
        assertThat(excluded.items().getFirst().state()).isEqualTo("FAILED");
        assertThatThrownBy(()->transfers.items(job.id(),0,20,"","",List.of("position,asc"),"invalid")).hasMessageContaining("Operador");
        var page=transfers.items(job.id(),1,1,"ACCEPTED"," ENVIADO Y VERIFICADO ",List.of("position,asc"));
        assertThat(page.meta().totalItems()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().position()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,"","%_",List.of("position,asc")).items()).hasSize(1);
        assertThat(transfers.items(job.id(),0,20,"FAILED","esperando",List.of("position,asc")).items()).isEmpty();
        assertThat(transfers.items(create().id(),0,20,"","verificado",List.of("position,asc")).items()).isEmpty();
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items").param("detail","esperando").param("state","ACCEPTED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
    }
    @Test void emptyJobsCanBeReadWithDefaultAndExplicitOrdering() throws Exception {
        mvc.perform(get("/api/calibre/transfers")).andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/calibre/transfers").param("sort","createdAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(0));
    }
    @Test void filtersIndividualJobItemsByStateWithAccurateCounts() throws Exception {
        var job=create();String url="/api/calibre/transfers/"+job.id()+"/items";
        mvc.perform(get(url).param("state","PENDING")).andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.totalItems").value(1)).andExpect(jsonPath("$.items[0].state").value("PENDING"));
        mvc.perform(get(url).param("state","FAILED")).andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.totalItems").value(0)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(url).param("state","INVALID")).andExpect(status().isBadRequest());
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
    }
    @Test void apiPreservesCommaSeparatedAndRepeatedSortCriteria() throws Exception {
        var job=create();
        mvc.perform(get("/api/calibre/transfers").param("sort","createdAt,desc","username,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/calibre/transfers")).andExpect(status().isOk());
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items").param("sort","message,asc","position,asc","revision,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(1));
        mvc.perform(get("/api/calibre/transfers/"+job.id()+"/items")).andExpect(status().isOk());
        mvc.perform(get("/api/calibre/transfers").param("sort","invalid,asc"))
            .andExpect(status().isBadRequest());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=EventContext.Origin.class,names={"MANUAL","SCHEDULED"})
    void taskCalibreJobsOnlyRecordProblemsForEitherOrigin(EventContext.Origin origin) {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CalibreTransfers.class);
        var previous=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
        for(boolean fail:List.of(false,true)) {
            capture.list.clear();
            String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO scheduled_tasks(id,name,operation,cron,timezone,owner_id,owner_username,enabled,created_at,updated_at) VALUES(?,'Calibre','CALIBRE_AUTO_UPDATE','0 5 * * *','UTC',?,'Owner',0,0,0)",task,owner.id());
            var job=EventContext.withOrigin(origin,()->com.rlibanez.eplsync.tasks.AutomationContext.run(this::create));
            jdbc.update("INSERT INTO scheduled_automation_runs(id,task_id,owner_id,state,created_at,configuration,context) VALUES(?,?,?,'WAITING',0,'{}',?)",run,task,owner.id(),origin.name());
            jdbc.update("INSERT INTO scheduled_automation_jobs VALUES(?,'calibre',?)",run,job.id());
            if(fail) when(files.download(record,0)).thenThrow(new com.rlibanez.eplsync.exception.UserInputException("No se puede leer el EPUB"));
            transfers.cycle();
            assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");
            var events=jdbc.queryForList("SELECT outcome,origin FROM app_events WHERE operation_id=? ORDER BY id",job.id());
            if(fail) {
                assertThat(events).extracting(e->e.get("outcome")).containsExactly("STARTED","PARTIAL");
                assertThat(events).allSatisfy(event->assertThat(event.get("origin")).isEqualTo(origin.name()));
            } else assertThat(events).isEmpty();
            assertThat(capture.list).filteredOn(e->e.getFormattedMessage().contains("acción=CALIBRE_SEND,") && e.getFormattedMessage().contains("resultado="+(fail?"PARTIAL":"SUCCEEDED")))
                .singleElement().satisfies(e->assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO));
            assertThat(capture.list).filteredOn(e->e.getFormattedMessage().contains("acción=CALIBRE_SEND_FILE,"))
                .isNotEmpty().allSatisfy(e->assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.DEBUG));
        }
        } finally {logger.detachAppender(capture);capture.stop();logger.setLevel(previous);}
    }
    @Test void sendsAndPublishesVerifiedBookWithoutSuccessfulHistoryEvent() {
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items()).singleElement().satisfies(item -> assertThat(item.calibreId()).isEqualTo(9));
        verify(client).uploadEpub(any(),any(),eq("book.epub"),anyString());
        assertThat(catalog.snapshot().complete()).isFalse();assertThat(catalog.snapshot().total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE action='CALIBRE_SEND_FILE'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT operation_id) FROM app_events WHERE action='CALIBRE_SEND' AND actor_id=?",Long.class,owner.id())).isZero();
    }
    @Test void twentyFiveSuccessfulFilesDoNotCreateHistoryEvents() {
        var choices=new java.util.ArrayList<CalibreTransfers.FileChoice>();
        var available=new java.util.ArrayList<EpubFiles.Item>();
        for(int index=0;index<25;index++) {
            choices.add(new CalibreTransfers.FileChoice(record,index));
            available.add(new EpubFiles.Item(index,"book.epub",bytes.length));
        }
        when(files.list(record)).thenReturn(available);
        when(files.download(eq(record),anyInt())).thenAnswer(call -> new EpubFiles.Opened("book.epub",bytes.length,new java.io.ByteArrayInputStream(bytes)));
        var job=EventContext.withActor(new EventContext.Actor(owner.id(),owner.username(),"USER"),
            () -> transfers.create(new CalibreTransfers.Request("main",choices)));
        for(int index=0;index<25;index++) transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT operation_id) FROM app_events WHERE actor_id=? AND action='CALIBRE_SEND'",Long.class,owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE actor_id=? AND action='CALIBRE_SEND_FILE'",Long.class,owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_events WHERE operation_id=? AND outcome='SUCCEEDED'",Long.class,job.id())).isZero();
    }
    @Test void preparationIdentifiesTheInaccessibleRecordInsteadOfAnAnonymousError() {
        when(files.list(record)).thenThrow(new com.rlibanez.eplsync.exception.TorrentOperationException(org.springframework.http.HttpStatus.CONFLICT,"No se puede leer el EPUB; revisa el montaje y los permisos de la carpeta"));
        var prepared=transfers.prepare(List.of(record));
        assertThat(prepared.files()).isEmpty();
        assertThat(prepared.unavailable()).singleElement().satisfies(item -> {
            assertThat(item.eplId()).isEqualTo(1L);assertThat(item.revision()).isEqualTo(1.1);
            assertThat(item.message()).contains("No se puede leer el EPUB");
        });
        assertThat(transfers.jobs(0,20,"",List.of("createdAt,desc")).meta().totalItems()).isZero();
    }
    @Test void preparationExcludesAnUnavailableEpubWithoutBlockingAvailableOnes() {
        var failed=new DownloadRecord();failed.setEplId(38147L);failed.setRevision(1.1);failed.setHash("B".repeat(40));failed.setClient("qbittorrent");failed.setClientInstanceId(tracking.instanceId());failed.setStatus(DownloadStatus.DOWNLOADED);failed.setOrigin(DownloadRecord.Origin.EPLSYNC);failed.setCreatedAt(Instant.now());
        String failedId=downloads.save(failed).getId();
        when(files.list(failedId)).thenThrow(new com.rlibanez.eplsync.exception.TorrentOperationException(org.springframework.http.HttpStatus.CONFLICT,"El torrent no contiene archivos EPUB completamente descargados"));
        var prepared=transfers.prepare(List.of(failedId,record,"removed-record"));
        assertThat(prepared.files()).singleElement().satisfies(item -> assertThat(item.recordId()).isEqualTo(record));
        assertThat(prepared.unavailable()).hasSize(2);
        assertThat(prepared.unavailable().getFirst().message()).isEqualTo("Los archivos aún no están descargados");
        var request=new CalibreTransfers.Request("main",prepared.files().stream().map(item -> new CalibreTransfers.FileChoice(item.recordId(),item.index())).toList(),List.of(),UUID.randomUUID().toString());
        var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
        var job=EventContext.withActor(actor,()->transfers.create(request));
        assertThat(EventContext.withActor(actor,()->transfers.create(request)).id()).isEqualTo(job.id());
        transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(files,never()).download(eq(failedId),anyInt());
    }
    @Test void oversizedEpubDoesNotExcludeItsAvailableSibling() {
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length),new EpubFiles.Item(1,"large.epub",268435457)));
        var prepared=transfers.prepare(List.of(record));
        assertThat(prepared.files()).singleElement().satisfies(item -> assertThat(item.index()).isZero());
        assertThat(prepared.unavailable()).singleElement().satisfies(item -> {assertThat(item.name()).isEqualTo("large.epub");assertThat(item.message()).contains("256 MiB");});
    }
    @Test void unavailableOrChangedSelectedEpubCannotBypassCreationChecks() {
        assertThat(transfers.prepare(List.of(record)).files()).hasSize(1);
        when(files.list(record)).thenThrow(new com.rlibanez.eplsync.exception.TorrentOperationException(org.springframework.http.HttpStatus.CONFLICT,"El torrent no contiene archivos EPUB completamente descargados"));
        assertThatThrownBy(this::create).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class).hasMessageContaining("ya no está disponible");
        assertThat(transfers.jobs(0,20,"",List.of("createdAt,desc")).meta().totalItems()).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void sizeWarningSurvivesTheSuccessfulTransferResult() {
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length,bytes.length+10)));
        assertThat(transfers.prepare(List.of(record)).files().getFirst().expectedSize()).isEqualTo(bytes.length+10);
        var job=create();
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("El tamaño del EPUB no coincide");
        transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("EPUB enviado y verificado","El tamaño del EPUB no coincide");
    }
    @Test void skipsLiveDuplicateWithoutUploading() {
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).skipped()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void preparationFailureExposesSafeReasonAndLogsTechnicalCause() {
        var job=create();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CalibreTransfers.class);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();logger.addAppender(captured);
        try {
            when(files.download(record,0)).thenThrow(new IllegalStateException("token=secret",new java.nio.file.AccessDeniedException("/private/password")));
            transfers.cycle();
            assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("permisos").doesNotContain("secret","/private");
            assertThat(captured.list.stream().filter(e -> e.getLevel()==ch.qos.logback.classic.Level.WARN).map(e -> e.getFormattedMessage()).toList())
                .anySatisfy(message -> assertThat(message).contains(job.id(),"AccessDeniedException","EPL ID=1").doesNotContain("secret","/private"));
            verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        } finally {logger.detachAppender(captured);captured.stop();}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void refusesMismatchedEpubIdentity(boolean automatic) throws Exception {
        settings.saveIdentifierOptions(new CalibreSettings.IdentifierOptions(automatic));
        bytes=epub(2,"1.10");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).failed()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void acceptsMatchingRealWorldIdAndEquivalentRevision() throws Exception {
        var row=downloads.findById(record).orElseThrow();row.setEplId(85481L);row.setRevision(1.0);downloads.saveAndFlush(row);
        bytes=epub(85481,"1.0");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),85481L,BigDecimal.ONE,true));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"2,1.1","2,1.2"})
    void identityMismatchExplainsExpectedAndFoundValuesWithoutUploading(long epl,String revision) throws Exception {
        bytes=epub(epl,revision);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var job=create();transfers.cycle();
        var item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst();
        assertThat(item.state()).isEqualTo("FAILED");
        assertThat(item.message()).contains("Esperado: EPL ID 1, revisión 1.1","Encontrado: EPL ID "+epl+", revisión "+revision,"No se ha enviado a Calibre");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"1.0","1.2"})
    void acceptsDifferentRevisionAndVerifiesTheActualEpubRevision(String revision) throws Exception {
        bytes=epub(1,revision);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal(revision),true));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        var item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst();
        assertThat(item.revision()).isEqualTo("1.1");assertThat(item.message()).contains("revisión del EPUB (r"+new BigDecimal(revision).stripTrailingZeros().toPlainString()+")","registro de descarga (r1.1)");
        verify(client).findRevision(any(),eq(1L),eq(new BigDecimal(revision)));
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        assertThat(downloads.findById(record).orElseThrow().getRevision()).isEqualTo(1.1);
    }
    @Test void alreadyPresentActualRevisionIsSkippedWithoutUploading() throws Exception {
        bytes=epub(1,"1.2");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.findRevision(any(),eq(1L),eq(new BigDecimal("1.2")))).thenReturn(9L);
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.2"),true));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).skipped()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void uncertainActualRevisionBlocksASecondJobWithADifferentRecordedRevision() throws Exception {
        bytes=epub(1,"1.2");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var first=create();transfers.cycle();assertThat(transfers.job(first.id()).uncertain()).isEqualTo(1);
        var row=downloads.findById(record).orElseThrow();row.setRevision(1.2);downloads.saveAndFlush(row);
        var second=create();transfers.cycle();
        assertThat(transfers.job(second.id()).state()).isEqualTo("PAUSED");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void mismatchedRevisionStillBlocksAutomaticCleanup(boolean automatic) throws Exception {
        settings.saveIdentifierOptions(new CalibreSettings.IdentifierOptions(automatic));
        bytes=epub(1,"1.2");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.2"),true));
        var job=create();jdbc.update("UPDATE calibre_job_items SET cleanup_policy='BOOK',cleanup_ids='[8]' WHERE job_id=?",job.id());
        transfers.cycle();transfers.cycle();
        var item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst();
        assertThat(item.state()).isEqualTo("CLEANUP_PENDING");assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(item.message()).contains("no se eliminará");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void uncertainDifferentRevisionUsesPersistedIdentityWithoutUploadingAgain() throws Exception {
        bytes=epub(1,"1.2");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        when(client.findRevision(any(),eq(1L),eq(new BigDecimal("1.2")))).thenReturn(9L);
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.2"),true));
        transfers.recoverInterrupted();transfers.control(job.id(),"resume");transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,never()).findRevision(any(),eq(1L),eq(new BigDecimal("1.1")));
    }
    @Test void differingRevisionDoesNotSkipAnUnrelatedRemoteRevision() throws Exception {
        bytes=epub(1,"1.2");when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.findRevision(any(),eq(1L),eq(new BigDecimal("1.2")))).thenReturn(9L);
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).failed()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void uncertainUploadWithoutIdentifiersCannotBeMatchedToAnotherRevisionRecord() throws Exception {
        bytes=withoutIdentifiers(true,true);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();
        when(client.findRevision(any(),anyLong(),any())).thenReturn(9L);
        transfers.control(job.id(),"resume");transfers.cycle();
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        verify(client,times(1)).findRevision(any(),anyLong(),any());
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void busyTorrentWhileOpeningAReadyEpubDefersWithoutFailingOrUploadingTwice() {
        var job=create();
        when(files.download(record,0)).thenThrow(new com.rlibanez.eplsync.exception.TorrentBusyException());
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("RUNNING");
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("PENDING");
        assertThat(transfers.job(job.id()).failed()).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        doAnswer(call -> new EpubFiles.Opened("book.epub",bytes.length,new java.io.ByteArrayInputStream(bytes))).when(files).download(record,0);
        jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE id=?",job.id());
        transfers.cycle();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void restartContinuesActivePendingWorkWithoutResubmittingCompletedItems() {
        var job=create();
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING',next_check=? WHERE id=?",Long.MAX_VALUE,job.id());
        transfers.recoverInterrupted();
        assertThat(transfers.job(job.id()).state()).isEqualTo("QUEUED");
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");
        transfers.recoverInterrupted();transfers.cycle();
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PAUSED","CANCELLED"})
    void restartPreservesInactiveJobsEvenWhenAnUploadWasInterrupted(String state) {
        var job=create();
        jdbc.update("UPDATE calibre_jobs SET state=? WHERE id=?",state,job.id());
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=1 WHERE job_id=?",job.id());
        clearInvocations(client,files);
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo(state);
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        verifyNoInteractions(client,files);
    }
    @Test void restartVerifiesPersistedRemoteIdWithoutNeedingMetadataOrUploadingAgain() {
        var job=create();
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=0,calibre_id=9 WHERE job_id=?",job.id());
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),null,null,false,"book-uuid"));
        when(client.completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("book-uuid"))).thenReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true,"book-uuid"));
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,never()).findRevision(any(),anyLong(),any());
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        verify(files,never()).download(anyString(),anyInt());
    }
    @Test void restartLeavesAnUnverifiableUploadForReviewInsteadOfRepeatingIt() {
        var job=create();
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=1 WHERE job_id=?",job.id());
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void restartDoesNotConfirmAnUncertainUploadWithoutAnEpub() {
        var job=create();
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=1 WHERE job_id=?",job.id());
        when(client.findRevision(any(),anyLong(),any())).thenReturn(9L);
        when(client.hasEpub(any(),eq(9L))).thenReturn(false);
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        assertThat(transfers.job(job.id()).accepted()).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void restartPreservesWarningAndDoesNotInferMissingIdentifiers() {
        var job=create();
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=0,message='El tamaño del EPUB no coincide con el registrado por el cliente torrent. El archivo podría haber sido modificado.' WHERE job_id=?",job.id());
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        transfers.recoverInterrupted();
        assertThat(transfers.job(job.id()).state()).isEqualTo("QUEUED");
        when(client.findRevision(any(),anyLong(),any())).thenReturn(9L);
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("tamaño del EPUB");
        verify(client,never()).findRevision(any(),anyLong(),any());
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void idleSchedulerVerifiesRecoveredUploadWithoutSendingItAgain() throws Exception {
        transfers.cycle();
        var job=create();
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=1 WHERE job_id=?",job.id());
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        transfers.recoverInterrupted();
        when(client.findRevision(any(),anyLong(),any())).thenReturn(9L);
        org.springframework.test.util.ReflectionTestUtils.setField(transfers,"enabled",true);
        try {
            transfers.schedule();
            var busy=(java.util.concurrent.atomic.AtomicBoolean)org.springframework.test.util.ReflectionTestUtils.getField(transfers,"busy");
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
            while(busy.get() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(busy.get()).isFalse();
            assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
            transfers.schedule();
            while(busy.get() && System.nanoTime()<deadline) Thread.sleep(10);
            verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(transfers,"enabled",false);}
    }
    @Test void restartVerifiesCompleteIdentityWithoutRepeatingUpload() {
        var job=create();
        jdbc.update("UPDATE calibre_job_items SET state='SENDING',identity_complete=1 WHERE job_id=?",job.id());
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        transfers.recoverInterrupted();
        assertThat(transfers.job(job.id()).state()).isEqualTo("QUEUED");
        when(client.findRevision(any(),anyLong(),any())).thenReturn(9L);
        transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void sqliteFailureAfterUploadIsVerifiedNextCycleWithoutPauseOrResending() throws Exception {
        bytes=withoutIdentifiers(true,true);
        when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var job=create();
        var busy=new org.springframework.dao.CannotAcquireLockException("busy",new java.sql.SQLException("busy","",517));
        doThrow(busy).doCallRealMethod().when(jdbc).update(eq("UPDATE calibre_job_items SET calibre_id=? WHERE id=?"),anyLong(),anyString());
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_job_items WHERE job_id=?",String.class,job.id())).isEqualTo("SENDING");
        // No identity lookup is possible: the accepted remote ID must survive the failed local write.
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("COMPLETED");
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void ambiguousUploadIsNeverAutomaticallyRepeatedAndCanBeVerified() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void pausesBeforeAnotherDestinationOrRevokedPermissions() {
        var job=create();settings.save(new CalibreSettings.Input("http://another:8080","","",true,"main"));
        transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        settings.save(new CalibreSettings.Input("http://localhost:8080","","",true,"main"));
        doReturn(new Account(owner.id(),owner.username(),owner.email(),"USER","ACTIVE",false,null,owner.securityVersion(),Set.of(Permission.CALIBRE_READ),null)).when(accounts).find(owner.id());
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void pauseAndCancelDoNotSendPendingFiles() {
        var job=create();transfers.control(job.id(),"pause");transfers.cycle();verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        transfers.control(job.id(),"cancel");transfers.cycle();assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().state()).isEqualTo("CANCELLED");
    }
    @Test void cancelAllowsTheAlreadyStartedUploadToFinishAndRecordsItsResult() {
        var job=create();
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenAnswer(invocation -> {
            transfers.control(job.id(),"cancel");
            return 9L;
        });
        transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("CANCELLED");
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        transfers.cycle();
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void cancelledUncertainUploadCanStillBeVerified() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();transfers.control(job.id(),"cancel");
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        transfers.control(job.id(),"resume");transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void rejectsInvalidSelectionBeforeCreatingJob() {
        assertThatThrownBy(() -> transfers.create(new CalibreTransfers.Request("main",List.of(new CalibreTransfers.FileChoice(record,0),new CalibreTransfers.FileChoice(record,0))))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThat(transfers.jobs(0,20,"",List.of("createdAt,desc")).meta().totalItems()).isZero();
    }
    @Test @WithMockUser(authorities="CALIBRE_READ") void readerCannotReadJobsOrSubmitOrControl() throws Exception {
        mvc.perform(get("/api/calibre/transfers")).andExpect(status().isForbidden());
        mvc.perform(post("/api/calibre/transfers/prepare").with(csrf()).contentType("application/json").content("{\"ids\":[\""+record+"\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/calibre/transfers/id/cancel").with(csrf())).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities="CALIBRE_SEND") void senderAlsoNeedsSourceFilesPermissionAndCsrf() throws Exception {
        mvc.perform(post("/api/calibre/transfers/prepare").with(csrf()).contentType("application/json").content("{\"ids\":[\""+record+"\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/calibre/transfers").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void databaseResetClearsJobsAndCascadeItems() {
        create();transfers.exclusive(() -> {jdbc.update("DELETE FROM calibre_jobs");return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Long.class)).isZero();
    }
    @Test void retentionPreservesUncertainCancelledTransfers() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();transfers.control(job.id(),"cancel");jdbc.update("UPDATE calibre_jobs SET updated_at=?",Instant.now().minus(java.time.Duration.ofDays(100)).toString());
        transfers.retention();assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
    }
    @Test void metadataOnlyDuplicateDoesNotPreventUploadingTheEpub() {
        when(client.findRevision(any(),eq(1L),any())).thenReturn(8L);
        when(client.hasEpub(any(),eq(8L))).thenReturn(false);
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(transfers.job(job.id()).skipped()).isZero();
        verify(client).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void uploadWithoutConfirmedEpubStaysUncertainAndCanBeRecoveredWithoutResending() {
        when(client.hasEpub(any(),eq(9L))).thenReturn(false);
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(job.id()).accepted()).isZero();
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        assertThat(catalog.snapshot()).isNull();
        transfers.control(job.id(),"resume");transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        when(client.hasEpub(any(),eq(9L))).thenReturn(true);
        transfers.control(job.id(),"resume");transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void uncertainIdentityMatchWithoutEpubIsNeverAcceptedOrResent() {
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());
        var job=create();transfers.cycle();
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        when(client.hasEpub(any(),eq(9L))).thenReturn(false);
        transfers.control(job.id(),"resume");transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.job(job.id()).accepted()).isZero();
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void failureToReadRemoteFormatsLeavesUploadUncertain() {
        when(client.hasEpub(any(),eq(9L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"No se pueden comprobar los formatos"));
        var job=create();transfers.cycle();
        assertThat(transfers.job(job.id()).state()).isEqualTo("RUNNING");
        assertThat(transfers.job(job.id()).accepted()).isZero();
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }

    @Test void directRequestsAreDurablyIdempotentEvenWithoutEpubIdentifiers() throws Exception {
        bytes=withoutIdentifiers(true,true);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var request=new CalibreTransfers.Request("main",List.of(new CalibreTransfers.FileChoice(record,0)),List.of(),UUID.randomUUID().toString());
        var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
        var first=EventContext.withActor(actor,()->transfers.create(request));
        assertThat(EventContext.withActor(actor,()->transfers.create(request)).id()).isEqualTo(first.id());
        transfers.cycle();
        assertThat(EventContext.withActor(actor,()->transfers.create(request)).id()).isEqualTo(first.id());
        assertThat(transfers.job(first.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        assertThatThrownBy(()->EventContext.withActor(actor,()->transfers.create(new CalibreTransfers.Request("other",request.files(),List.of(),request.id())))).hasMessageContaining("otra selección");
    }
    @Test void localCalibreCapacityDefersWithoutPausingOrConsumingRetries() {
        var job=create();when(client.findRevision(any(),anyLong(),any())).thenThrow(new CalibreClient.Busy());
        for(int n=0;n<6;n++) {jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE id=?",job.id());jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE job_id=?",job.id());transfers.cycle();}
        assertThat(transfers.job(job.id()).state()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT transient_attempts FROM calibre_job_items WHERE job_id=?",Integer.class,job.id())).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        doReturn(null).when(client).findRevision(any(),anyLong(),any());
        jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE id=?",job.id());jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE job_id=?",job.id());
        transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
    }
    @Test void temporaryVerificationFailureRetriesWithoutRepeatingUpload() {
        var job=create();when(client.book(any(),eq(9L))).thenThrow(new CalibreClient.TransientFailure(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Temporary",null));
        transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("RUNNING");assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        doReturn(new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true)).when(client).book(any(),eq(9L));
        jdbc.update("UPDATE calibre_jobs SET next_check=0 WHERE id=?",job.id());jdbc.update("UPDATE calibre_job_items SET next_check=0 WHERE job_id=?",job.id());
        transfers.recoverInterrupted();transfers.cycle();assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void queuedJobsGetTurnsWithoutAggregatingWholeLargeJobForEveryFile() {
        var heavy=create();
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state) VALUES(?,?,1,?,1,'book.epub',?,1,'1.1','PENDING')",UUID.randomUUID().toString(),heavy.id(),record,bytes.length);
        var small=create();clearInvocations(jdbc);
        transfers.cycle();
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(call -> call.getArguments().length>0 && call.getArgument(0) instanceof String sql && sql.startsWith("SELECT j.*,count(i.id)"));
        transfers.cycle();assertThat(transfers.job(small.id()).accepted()).isEqualTo(1);assertThat(transfers.job(heavy.id()).pending()).isEqualTo(1);
    }
    @Test void explicitResolutionVerifiesUnidentifiedUploadWithoutSendingAgain() throws Exception {
        bytes=withoutIdentifiers(true,true);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var job=create();when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());transfers.cycle();
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
        transfers.resolve(job.id(),new CalibreTransfers.Resolution(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().id(),"verify",9L,true));
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);assertThat(transfers.job(job.id()).uncertain()).isZero();
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        var history=context.getBean(JobRetryHistory.class);
        var attempt=history.attempts("calibre",job.id(),0,20).items().getFirst();
        assertThat(attempt.state()).isEqualTo("COMPLETED");
        assertThat(history.details("calibre",job.id(),attempt.id(),0,20).items()).singleElement().satisfies(detail->{assertThat(detail.beforeState()).isEqualTo("UNCERTAIN");assertThat(detail.state()).isEqualTo("ACCEPTED");assertThat(detail.beforeMessage()).contains("No se ha confirmado");});
    }
    @Test void explicitResolutionRequiresConfirmationAndRejectsWrongBook() {
        var job=create();when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());transfers.cycle();
        String item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().id();
        assertThatThrownBy(()->transfers.resolve(job.id(),new CalibreTransfers.Resolution(item,"retry",null,false))).hasMessageContaining("Confirma");
        when(client.book(any(),eq(10L))).thenReturn(new CalibreClient.Book(10,"Other",List.of(),2L,new BigDecimal("1.1"),true));
        assertThatThrownBy(()->transfers.resolve(job.id(),new CalibreTransfers.Resolution(item,"verify",10L,true))).hasMessageContaining("no coinciden");
        assertThat(transfers.job(job.id()).uncertain()).isEqualTo(1);
    }

    @Test void concurrentDirectRequestsCreateOneJob() throws Exception {
        var request=new CalibreTransfers.Request("main",List.of(new CalibreTransfers.FileChoice(record,0)),List.of(),UUID.randomUUID().toString());
        var actor=new EventContext.Actor(owner.id(),owner.username(),"USER");
        var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var ready=new java.util.concurrent.CountDownLatch(2);
        when(files.list(record)).thenAnswer(call->{ready.countDown();if(!ready.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");return List.of(new EpubFiles.Item(0,"book.epub",bytes.length));});
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<CalibreTransfers.Job> create=()->{
                org.springframework.security.core.context.SecurityContextHolder.setContext(security);
                try {return EventContext.withActor(actor,()->transfers.create(request));}finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            };
            var first=executor.submit(create);var second=executor.submit(create);
            assertThat(first.get(15,java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(second.get(15,java.util.concurrent.TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);
        transfers.cycle();verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void explicitUnconfirmedRetryFirstChecksWhetherMatchingBookAlreadyExists() {
        var job=create();when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());transfers.cycle();
        when(client.findRevision(any(),eq(1L),any())).thenReturn(9L);
        var item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst();
        transfers.resolve(job.id(),new CalibreTransfers.Resolution(item.id(),"retry",null,true));
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void explicitUnconfirmedRetryRequiresAbsenceConfirmationAndKeepsOriginalJob() throws Exception {
        bytes=withoutIdentifiers(true,true);when(files.list(record)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",bytes.length)));
        var job=create();when(client.uploadEpub(any(),any(),anyString(),anyString())).thenThrow(new CalibreClient.UnconfirmedUpload());transfers.cycle();
        String item=transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().id();
        transfers.control(job.id(),"cancel");
        transfers.resolve(job.id(),new CalibreTransfers.Resolution(item,"retry",null,true));
        doReturn(9L).when(client).uploadEpub(any(),any(),anyString(),anyString());transfers.cycle();
        assertThat(transfers.job(job.id()).accepted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);
        verify(client,times(2)).uploadEpub(any(),any(),anyString(),anyString());
        var history=context.getBean(JobRetryHistory.class);var attempt=history.attempts("calibre",job.id(),0,20).items().getFirst();
        assertThat(history.details("calibre",job.id(),attempt.id(),0,20).items()).singleElement().satisfies(detail->{assertThat(detail.phase()).isEqualTo("SEND");assertThat(detail.beforeState()).isEqualTo("UNCERTAIN");assertThat(detail.state()).isEqualTo("ACCEPTED");});
    }

    @Test void permanentConfigurationFailurePausesImmediatelyWithItsActionableReason() {
        var job=create();when(client.findRevision(any(),anyLong(),any())).thenThrow(new CalibreClient.PermanentFailure("La URL no corresponde al servidor de contenido de Calibre; comprueba el puerto"));
        transfers.cycle();assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        assertThat(transfers.items(job.id(),0,20,List.of("position,asc")).items().getFirst().message()).contains("comprueba el puerto");
        assertThat(jdbc.queryForObject("SELECT transient_attempts FROM calibre_job_items WHERE job_id=?",Integer.class,job.id())).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }

    @Test void shutdownStopsTheBoundedBatchBeforeAnotherCycleStarts() throws Exception {
        var isolated=spy(new CalibreTransfers(jdbc,context.getBean(org.springframework.transaction.PlatformTransactionManager.class),settings,client,catalog,files,downloads,tracking,torrentProperties,accounts,context.getBean(EventJournal.class)));
        org.springframework.test.util.ReflectionTestUtils.setField(isolated,"enabled",true);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");return null;}).when(isolated).cycle();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            isolated.schedule();assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var closing=executor.submit(isolated::close);
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
            while(Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.getField(isolated,"enabled")) && System.nanoTime()<deadline) Thread.onSpinWait();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(isolated,"enabled")).isEqualTo(false);
            release.countDown();closing.get(5,java.util.concurrent.TimeUnit.SECONDS);
            verify(isolated,times(1)).cycle();
        } finally {release.countDown();isolated.close();}
    }
}
