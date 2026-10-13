package com.rlibanez.eplsync.settings;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.security.require-https=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.updates.scheduler-enabled=false"})
class CalibreEpubApiTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean CalibreEpubDownloads downloads;
    MockMvc mvc;
    @BeforeEach void setup() { reset(downloads);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test @WithMockUser(authorities="CALIBRE_READ") void readPermissionDoesNotAllowDownloads() throws Exception {
        mvc.perform(post("/api/calibre/epub-downloads").with(csrf()).contentType("application/json").content("{\"ids\":[3]}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/calibre/epub-downloads/token")).andExpect(status().isForbidden());
        verifyNoInteractions(downloads);
    }
    @Test @WithMockUser(authorities="CALIBRE_FILES_READ") void filePermissionAlsoRequiresLibraryRead() throws Exception {
        mvc.perform(get("/api/calibre/epub-downloads/token")).andExpect(status().isForbidden());verifyNoInteractions(downloads);
    }
    @Test @WithMockUser(authorities={"CALIBRE_READ","CALIBRE_FILES_READ"}) void preparesWithCsrfAndStreamsUsingSameSession() throws Exception {
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/api/calibre/epub-downloads").session(session).contentType("application/json").content("{\"ids\":[3,8]}"))
            .andExpect(status().isForbidden());
        when(downloads.prepare(session.getId(),java.util.List.of(3L,8L))).thenReturn(new CalibreEpubDownloads.Ready("/api/calibre/epub-downloads/token",2,true));
        mvc.perform(post("/api/calibre/epub-downloads").session(session).with(csrf()).contentType("application/json").content("{\"ids\":[3,8]}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.fileCount").value(2));
        doAnswer(call -> {var response=(jakarta.servlet.http.HttpServletResponse)call.getArgument(2);response.setContentType("application/zip");response.getOutputStream().write("zip".getBytes());return null;})
            .when(downloads).download(eq(session.getId()),eq("token"),any());
        mvc.perform(get("/api/calibre/epub-downloads/token").session(session)).andExpect(status().isOk()).andExpect(content().string("zip"));
        verify(downloads).download(eq(session.getId()),eq("token"),any());
    }
}
