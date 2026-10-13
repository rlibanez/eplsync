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
    @Test @WithMockUser(authorities={"DOWNLOADS_FILES_READ","TORRENT_SYNC"})
    void streamsZipWithDistinctNamesAndSessionBoundSingleUseLink() throws Exception {
        var first=new EpubFiles.Planned("one",new com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile(0,"book.epub",3,1,"/downloads"));
        var second=new EpubFiles.Planned("two",new com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile(0,"other/book.epub",3,1,"/downloads"));
        var plan=new EpubFiles.Plan("server","/downloads","/local",true,List.of(first,second));
        when(files.prepare(List.of("one","two"))).thenReturn(plan);
        when(files.open(first)).thenAnswer(call -> new EpubFiles.Opened("book.epub",3,new java.io.ByteArrayInputStream("one".getBytes())));
        when(files.open(second)).thenAnswer(call -> new EpubFiles.Opened("book.epub",3,new java.io.ByteArrayInputStream("two".getBytes())));
        var session=new org.springframework.mock.web.MockHttpSession();
        var prepared=mvc.perform(post("/api/torrent/downloads/epub-batches").session(session).contentType("application/json").content("{\"ids\":[\"one\",\"two\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var url=new tools.jackson.databind.ObjectMapper().readTree(prepared).get("url").asString();
        mvc.perform(get(url).session(new org.springframework.mock.web.MockHttpSession())).andExpect(status().isNotFound());
        var bytes=mvc.perform(get(url).session(session)).andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            org.assertj.core.api.Assertions.assertThat(zip.getNextEntry().getName()).isEqualTo("book.epub");
            org.assertj.core.api.Assertions.assertThat(new String(zip.readAllBytes())).isEqualTo("one");
            org.assertj.core.api.Assertions.assertThat(zip.getNextEntry().getName()).isEqualTo("book (2).epub");
            org.assertj.core.api.Assertions.assertThat(new String(zip.readAllBytes())).isEqualTo("two");
            org.assertj.core.api.Assertions.assertThat(zip.getNextEntry()).isNull();
        }
        mvc.perform(get(url).session(session)).andExpect(status().isNotFound());
        verify(files).validate(plan);
    }
    @Test @WithMockUser(authorities="TORRENT_SYNC") void deniesBatchWithoutFilePermission() throws Exception {
        mvc.perform(post("/api/torrent/downloads/epub-batches").contentType("application/json").content("{\"ids\":[\"one\"]}"))
                .andExpect(status().isForbidden());verifyNoInteractions(files);
    }
    @Test @WithMockUser(authorities={"DOWNLOADS_FILES_READ","TORRENT_SYNC"})
    void singleBatchStreamsEpubDirectlyAndValidationFailureSendsNoAttachment() throws Exception {
        var entry=new EpubFiles.Planned("one",new com.rlibanez.eplsync.torrent.TorrentClient.TorrentFile(0,"book.epub",3,1,"/downloads"));
        var plan=new EpubFiles.Plan("server","/downloads","/local",false,List.of(entry));
        when(files.prepare(List.of("one"))).thenReturn(plan);
        when(files.open(entry)).thenReturn(new EpubFiles.Opened("book.epub",3,new java.io.ByteArrayInputStream("one".getBytes())));
        var session=new org.springframework.mock.web.MockHttpSession();
        var prepared=mvc.perform(post("/api/torrent/downloads/epub-batches").session(session).contentType("application/json").content("{\"ids\":[\"one\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var url=new tools.jackson.databind.ObjectMapper().readTree(prepared).get("url").asString();
        mvc.perform(get(url).session(session)).andExpect(status().isOk()).andExpect(content().contentType("application/epub+zip"))
                .andExpect(content().string("one")).andExpect(header().string("Content-Length","3"));
        prepared=mvc.perform(post("/api/torrent/downloads/epub-batches").session(session).contentType("application/json").content("{\"ids\":[\"one\"]}"))
                .andReturn().getResponse().getContentAsString();
        url=new tools.jackson.databind.ObjectMapper().readTree(prepared).get("url").asString();
        doThrow(new com.rlibanez.eplsync.exception.TorrentOperationException(org.springframework.http.HttpStatus.CONFLICT,"Archivo inaccesible"))
                .when(files).validate(plan);
        mvc.perform(get(url).session(session)).andExpect(status().isConflict()).andExpect(header().doesNotExist("Content-Disposition"));
        verify(files,times(1)).open(entry);
    }
}
