package com.rlibanez.eplsync.torrent.updates;

import java.time.Instant;
import java.util.*;
import com.rlibanez.eplsync.security.UpdatePreferences;
import com.rlibanez.eplsync.torrent.downloads.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@WithMockUser(authorities={"CATALOG_READ","TORRENT_SYNC"})
class RevisionUpdatesTests {
    @Autowired RevisionUpdates updates;
    @Autowired DownloadRepository downloads;
    @Autowired CatalogBookRepository books;
    @Autowired BulkItemRepository items;
    @Autowired BulkJobRepository jobs;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean TorrentClientService client;
    MockMvc mvc;
    @BeforeEach void setup() {
        items.deleteAll();jobs.deleteAll();downloads.deleteAll();books.deleteAll();reset(client);
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
    }
    void book(long id,double revision) {
        books.save(CatalogBook.builder().eplId(id).revision(revision).title("Book "+id).author("Author").links("A".repeat(40)).build());
    }
    DownloadRecord history(long id,double revision,DownloadStatus state,boolean accepted) {
        var row=new DownloadRecord();row.setEplId(id);row.setRevision(revision);row.setHash(UUID.randomUUID().toString());row.setClient("qbittorrent");
        row.setClientInstanceId("old-server");row.setStatus(state);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());
        if(accepted)row.setSubmittedAt(Instant.now());return downloads.save(row);
    }
    @Test void includesAbsentAndIncompleteButSuppressesKnownNewerRevisionsAcrossAllStatesAndDestinations() {
        for(long id=1;id<=5;id++)book(id,1.2);
        var covered=books.findById(1L).orElseThrow();covered.setCoverUrl("https://covers.example/1.jpg");covered.setCoverAvailable(true);books.save(covered);
        history(1,1.1,DownloadStatus.NOT_FOUND,true);
        history(2,1.1,DownloadStatus.SUBMITTED,true);
        history(3,1.1,DownloadStatus.DOWNLOADED,true);
        var newer=history(3,1.2,DownloadStatus.NOT_FOUND,true);newer.setClientInstanceId("new-server");downloads.save(newer);
        history(4,1.1,DownloadStatus.DOWNLOADED,true);history(4,1.2,DownloadStatus.ERROR,false);
        history(5,1.1,DownloadStatus.ERROR,false);
        var result=updates.search(UpdatePreferences.defaults(),0,20,"eplId,asc");
        assertThat(result.items()).extracting((RevisionUpdates.Row entryValue) -> java.util.Objects.requireNonNull(entryValue).eplId()).containsExactly(1L,2L,4L);
        assertThat(result.items().getFirst().registeredRevision()).isEqualTo(1.1);
        assertThat(result.items().getFirst().coverUrl()).isEqualTo("https://covers.example/1.jpg");
        assertThat(result.items().getFirst().coverAvailable()).isTrue();
        var completed=updates.search(new UpdatePreferences.Preferences(List.of(DownloadStatus.DOWNLOADED)),0,20,"eplId,asc");
        assertThat(completed.items()).extracting((RevisionUpdates.Row entryValue) -> java.util.Objects.requireNonNull(entryValue).eplId()).containsExactly(4L);
    }
    @Test void filtersRegisteredStatusWithoutChangingReferenceRevisionBeforePagination() {
        book(1,2);history(1,1,DownloadStatus.SUBMITTED,true);history(1,1.5,DownloadStatus.DOWNLOADED,true);
        book(2,2);history(2,1,DownloadStatus.SUBMITTED,true);
        book(3,2);history(3,1,DownloadStatus.SUBMITTED,true);
        var result=updates.search(UpdatePreferences.defaults(),1,1,"eplId,asc",null,DownloadStatus.SUBMITTED);
        assertThat(result.meta().totalItems()).isEqualTo(2);
        assertThat(result.items()).extracting((RevisionUpdates.Row entryValue) -> java.util.Objects.requireNonNull(entryValue).eplId()).containsExactly(3L);
        assertThat(updates.search(UpdatePreferences.defaults(),0,20,"eplId,asc",null,DownloadStatus.DOWNLOADED).items())
            .extracting((RevisionUpdates.Row entryValue) -> java.util.Objects.requireNonNull(entryValue).eplId()).containsExactly(1L);
    }
    @Test void selectsHighestMatchingRevisionOnceAndPaginatesBeforeReturningRows() {
        for(long id=1;id<=3;id++) {book(id,2);history(id,1,DownloadStatus.DOWNLOADED,true);history(id,1.1,DownloadStatus.DOWNLOADED,true);history(id,1.1,DownloadStatus.DOWNLOADED,true);}
        var result=updates.search(UpdatePreferences.defaults(),1,1,"eplId,desc");
        assertThat(result.meta().totalItems()).isEqualTo(3);
        assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().eplId()).isEqualTo(2);
        assertThat(result.items().getFirst().registeredRevision()).isEqualTo(1.1);
        assertThatThrownBy(() -> updates.search(UpdatePreferences.defaults(),0,1001,"eplId,asc")).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(() -> updates.search(UpdatePreferences.defaults(),0,20,"anything,desc")).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    @Test void discoveredTorrentsCountAndActiveJobsDoNotOfferDuplicateSubmissions() {
        book(1,2);var row=history(1,1,DownloadStatus.DOWNLOADED,false);row.setDiscoveredAt(Instant.now());downloads.save(row);
        assertThat(updates.search(UpdatePreferences.defaults(),0,20,"eplId,asc").items()).hasSize(1);
        var job=new BulkJob();job.setId("job");job.setState(BulkJob.State.PAUSED);jobs.save(job);
        var item=new BulkItem();item.setId("item");item.setJobId("job");item.setEplId(1L);item.setState(BulkItem.State.PENDING);item.setCommandJson("{\"book\":{\"revision\":2}}");items.save(item);
        assertThat(updates.search(UpdatePreferences.defaults(),0,20,"eplId,asc").items()).isEmpty();
        item.setCommandJson("{\"book\":{\"revision\":1}}");items.save(item);
        assertThat(updates.search(UpdatePreferences.defaults(),0,20,"eplId,asc").items()).hasSize(1);
        job.setState(BulkJob.State.CANCELLED);jobs.save(job);
        assertThat(updates.search(UpdatePreferences.defaults(),0,20,"eplId,asc").items()).hasSize(1);
    }
    @Test void synchronizationPrecedesSearchAndFailuresAbortIt() throws Exception {
        book(1,2);history(1,1,DownloadStatus.SUBMITTED,true);
        mvc.perform(post("/api/torrent/revision-updates/search?synchronize=true").contentType("application/json").content("{\"states\":[\"SUBMITTED\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eplId").value(1));
        verify(client).syncDownloads(false,false);
        reset(client);
        mvc.perform(post("/api/torrent/revision-updates/search?synchronize=true").contentType("application/json").content("{\"states\":[]}"))
            .andExpect(status().isBadRequest());verifyNoInteractions(client);
        when(client.syncDownloads(false,false)).thenThrow(new com.rlibanez.eplsync.exception.UserInputException("Sync failed"));
        mvc.perform(post("/api/torrent/revision-updates/search?synchronize=true").contentType("application/json").content("{\"states\":[\"SUBMITTED\"]}"))
            .andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","TORRENT_SYNC"}) void unifiedPermissionAllowsReadingAndSynchronization() throws Exception {
        mvc.perform(post("/api/torrent/revision-updates/search?synchronize=true").contentType("application/json").content("{\"states\":[\"SUBMITTED\"]}"))
            .andExpect(status().isOk());verify(client).syncDownloads(false,false);reset(client);
        mvc.perform(post("/api/torrent/revision-updates/search").contentType("application/json").content("{\"states\":[\"SUBMITTED\"]}"))
            .andExpect(status().isOk());
    }
}
