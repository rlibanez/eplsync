package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.security.require-https=false","eplsync.torrent.enabled=false","eplsync.torrent.inventory.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.tasks.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class BookDownloadInventoryTests {
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired DownloadFileInventory inventory;
    @Autowired CatalogDownloadViewService views;
    @MockitoBean TorrentClientService torrent;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM torrent_downloads");jdbc.update("DELETE FROM catalog_books");
        jdbc.update("INSERT INTO catalog_books(epl_id,revision,title,author) VALUES(123,1,'Book','Author')");
        for(String id:List.of("first","second","pending","failed")) jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,123,1,?,'qbittorrent','instance','DOWNLOADED','EPLSYNC',0)",id,id);
        var files=new ArrayList<TorrentFile>();
        for(int n=0;n<25;n++) files.add(new TorrentFile(n,"folder/"+n+(n%2==0?".epub":".pdf"),100+n,.5,"/private/mount"));
        inventory.record("first","instance","first",files);
        inventory.record("second","instance","second",List.of(new TorrentFile(0,"other/comic.cbr",200,1,"/private/mount")));
        jdbc.update("UPDATE torrent_downloads SET inventory_state='ERROR' WHERE id='failed'");
        clearInvocations(torrent);
    }
    @AfterEach void neverTouchesTheTorrentClient() {verifyNoInteractions(torrent);}

    @Test @WithMockUser(authorities={"CATALOG_READ","BOOK_HISTORY_READ"})
    void historyExposesSeparateExtensionsAndTheInventoryIsPaginatedAndScopedToTheBook() throws Exception {
        mvc.perform(get("/api/catalog/books/123/history").param("sort","fileExtensions,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
        assertThat(views.history(123L,0,20).items()).filteredOn(item->item.id().equals("first")).singleElement().satisfies(item->assertThat(item.fileExtensions()).containsExactly("epub","pdf"));
        var response=mvc.perform(get("/api/catalog/books/123/history/first/files").param("page","1").param("size","20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.inventoryState").value("CHECKED"))
            .andExpect(jsonPath("$.meta.totalItems").value(25)).andExpect(jsonPath("$.meta.totalPages").value(2))
            .andExpect(jsonPath("$.items.length()").value(5)).andExpect(jsonPath("$.items[0].index").value(20))
            .andExpect(jsonPath("$.items[0].name").value("folder/20.epub")).andExpect(jsonPath("$.items[0].size").value(120)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("/private/mount","savePath","localRoot","clientInstanceId");
        mvc.perform(get("/api/catalog/books/123/history/second/files")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].extension").value("cbr"));
        mvc.perform(get("/api/catalog/books/999/history/first/files")).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/books/123/history/missing/files")).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/books/123/history/first/files").param("size","1001")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books/123/history/first/files").param("page","-1")).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","BOOK_HISTORY_READ"})
    void unknownAndFailedInventoriesRemainDistinguishableAndRetainKnownFiles() throws Exception {
        mvc.perform(get("/api/catalog/books/123/history/pending/files")).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryState").value("PENDING")).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/catalog/books/123/history/failed/files")).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryState").value("ERROR")).andExpect(jsonPath("$.items").isEmpty());
        jdbc.update("UPDATE torrent_downloads SET inventory_state='ERROR' WHERE id='second'");
        mvc.perform(get("/api/catalog/books/123/history/second/files")).andExpect(status().isOk()).andExpect(jsonPath("$.inventoryState").value("ERROR")).andExpect(jsonPath("$.items[0].name").value("other/comic.cbr"));
        mvc.perform(get("/api/torrent/downloads/first/epubs/0")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ"})
    void catalogPermissionAloneDoesNotRevealTheInventory() throws Exception {
        mvc.perform(get("/api/catalog/books/123/history/first/files")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities={"BOOK_HISTORY_READ","DOWNLOADS_FILES_READ"})
    void historyAndFilePermissionDoNotReplaceCatalogPermission() throws Exception {
        mvc.perform(get("/api/catalog/books/123/history/first/files")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","TORRENT_SYNC"})
    void torrentSyncDoesNotReplaceBookHistoryPermission() throws Exception {
        mvc.perform(get("/api/catalog/books/123/history/first/files")).andExpect(status().isForbidden());
    }
}
