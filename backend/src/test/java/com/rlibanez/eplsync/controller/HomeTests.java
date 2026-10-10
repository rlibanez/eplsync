package com.rlibanez.eplsync.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=validate"})
class HomeTests {
    @Autowired WebApplicationContext context;
    @Autowired com.rlibanez.eplsync.repository.CatalogBookRepository books;
    @org.junit.jupiter.api.BeforeEach void clear() { books.deleteAllInBatch(); }
    @Test void populatedCatalogSummaryCountsBooksWithoutLoadingTheirData() throws Exception {
        books.saveAndFlush(com.rlibanez.eplsync.model.CatalogBook.builder().eplId(1L)
            .revision(1.0).author("Author").title("Title").build());
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
            .perform(get("/api/home/summary").with(user("reader").authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.catalog.total").value(1))
            .andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void emptyInstallationServesConcurrentHomeReadsWithoutRateLimit() throws Exception {
        MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(10)) {
            var ready=new java.util.concurrent.CyclicBarrier(10);
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<10;i++) tasks.add(executor.submit(() -> {
                ready.await();
                mvc.perform(get("/api/home/summary").with(user("admin").authorities(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"),
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_SYNC"),
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("TORRENT_JOBS_MANAGE"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.catalog.total").value(0))
                    .andExpect(jsonPath("$.downloads.total").value(0));
                return null;
            }));
            for(var task:tasks) task.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void catalogReaderDoesNotReceiveDownloadOrJobBlocks() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
            .perform(get("/api/home/summary").with(user("reader").authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.catalog.total").value(0))
            .andExpect(jsonPath("$.downloads").doesNotExist()).andExpect(jsonPath("$.jobs").doesNotExist());
    }
}
