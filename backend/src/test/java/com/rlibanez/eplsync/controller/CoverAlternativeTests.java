package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "spring.jpa.hibernate.ddl-auto=validate"})
class CoverAlternativeTests {
    @Autowired CatalogBookRepository repository;
    @Autowired TransactionTemplate transactions;
    MockMvc mvc;

    @BeforeEach void setup() {
        repository.deleteAllInBatch();
        repository.saveAndFlush(CatalogBook.builder().eplId(1L).title("Libro").author("Autor").revision(1.0)
                .coverUrl("https://example.org/cover.jpg").coverAvailable(true).build());
        mvc = MockMvcBuilders.standaloneSetup(new CoverAlternativeController(repository, transactions))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void explicitOverridePreservesUrlAndIsIdempotent() throws Exception {
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/catalog/covers/1/alternative")
                .contentType("application/json").content("{\"expectedCoverUrl\":\"https://example.org/cover.jpg\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coverAvailable").value(false));
        var book = repository.findById(1L).orElseThrow();
        assertThat(book.getCoverAvailable()).isFalse();
        assertThat(book.getCoverUrl()).isEqualTo("https://example.org/cover.jpg");
    }

    @Test void staleOrMissingUrlCannotOverrideAnotherCover() throws Exception {
        mvc.perform(post("/api/catalog/covers/1/alternative").contentType("application/json")
                .content("{\"expectedCoverUrl\":\"https://example.org/old.jpg\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/catalog/covers/1/alternative").contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/catalog/covers/99/alternative").contentType("application/json")
                .content("{\"expectedCoverUrl\":\"https://example.org/cover.jpg\"}")).andExpect(status().isNotFound());
        assertThat(repository.findById(1L).orElseThrow().getCoverAvailable()).isTrue();
    }
}
