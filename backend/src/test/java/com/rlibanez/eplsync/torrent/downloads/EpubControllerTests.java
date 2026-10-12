package com.rlibanez.eplsync.torrent.downloads;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class EpubControllerTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean EpubFiles files;
    MockMvc mvc;
    @BeforeEach void setup() { reset(files);mvc=MockMvcBuilders.webAppContextSetup(context).build(); }
    @Test @WithMockUser(authorities="TORRENT_SYNC") void deniesReadOnlyHistoryAccessToBytes() throws Exception {
        mvc.perform(get("/api/torrent/downloads/record/epubs")).andExpect(status().isForbidden());
        mvc.perform(get("/api/torrent/downloads/record/epubs/7")).andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }
    @Test @WithMockUser(authorities="DOWNLOADS_FILES_READ") void requiresAccessToDownloadRecordsAsWell() throws Exception {
        mvc.perform(get("/api/torrent/downloads/record/epubs")).andExpect(status().isForbidden());verifyNoInteractions(files);
    }
    @Test @WithMockUser(authorities={"DOWNLOADS_FILES_READ","BOOK_HISTORY_READ"}) void listsAndStreamsWithSafeHeaders() throws Exception {
        when(files.list("record")).thenReturn(List.of(new EpubFiles.Item(7,"libro.epub",5)));
        mvc.perform(get("/api/torrent/downloads/record/epubs")).andExpect(status().isOk()).andExpect(jsonPath("$[0].index").value(7));
        when(files.download("record",7)).thenReturn(new EpubFiles.Opened("libro.epub",5,new java.io.ByteArrayInputStream("bytes".getBytes())));
        mvc.perform(get("/api/torrent/downloads/record/epubs/7")).andExpect(status().isOk())
            .andExpect(content().string("bytes")).andExpect(header().string("Content-Type","application/epub+zip"))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Content-Length","5"));
    }
}
