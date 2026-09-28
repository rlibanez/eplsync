package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.CatalogMagnetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.web.SortHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "eplsync.torrent.trackers="
})
@Transactional
class CatalogMagnetControllerTests {
    @Autowired CatalogBookRepository repository;
    @Autowired CatalogMagnetService service;
    private MockMvc mvc;
    private final String first = "magnet:?xt=urn:btih:" + "A".repeat(40) + "&dn=EPL_1_Uno";
    private final String second = "magnet:?xt=urn:btih:" + "B".repeat(40) + "&dn=EPL_1_Uno";

    @BeforeEach
    void setup() {
        repository.deleteAll();
        repository.save(CatalogBook.builder().eplId(1L).revision(1.0).author("Autor").title("Uno")
                .links("A".repeat(40) + ", " + "B".repeat(40)).build());
        repository.save(CatalogBook.builder().eplId(2L).revision(1.0).author("Otro").title("Dos")
                .links("a".repeat(40) + ",invalid").build());
        repository.save(CatalogBook.builder().eplId(3L).revision(1.0).author("Otro").title("Tres").build());
        repository.flush();
        mvc = MockMvcBuilders.standaloneSetup(new CatalogMagnetController(service))
                .setCustomArgumentResolvers(new SortHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsBookMagnetsEmptyListAndNotFound() throws Exception {
        mvc.perform(get("/api/catalog/books/1/magnets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0]").value(first));
        mvc.perform(get("/api/catalog/books/3/magnets")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/catalog/books/99/magnets")).andExpect(status().isNotFound());
    }

    @Test
    void filtersSortsAndDeduplicatesHashesAcrossBooks() throws Exception {
        mvc.perform(get("/api/catalog/magnets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0]").value(first));
        mvc.perform(get("/api/catalog/magnets").param("author", "Otro"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(first.replace("EPL_1_Uno", "EPL_2_Dos")));
        mvc.perform(get("/api/catalog/magnets").param("sort", "title,asc"))
                .andExpect(jsonPath("$[0]").value(first.replace("EPL_1_Uno", "EPL_2_Dos")));
    }

    @Test
    void paginatesMagnetsAfterExpansionAndDeduplication() throws Exception {
        mvc.perform(get("/api/catalog/magnets").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0]").value(second))
                .andExpect(jsonPath("$.meta.totalItems").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mvc.perform(get("/api/catalog/magnets").param("page", "2147483647").param("size", "500"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.meta.totalItems").value(2));
        mvc.perform(get("/api/catalog/magnets").param("size", "1"))
                .andExpect(jsonPath("$.meta.page").value(0));
        mvc.perform(get("/api/catalog/magnets").param("page", "0"))
                .andExpect(jsonPath("$.meta.size").value(20));
        for (String size : new String[] {"0", "-1", "invalid"}) {
            mvc.perform(get("/api/catalog/magnets").param("size", size)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/catalog/magnets").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/magnets").param("publicationYear", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void exportsFilteredNewlineSeparatedMagnetsAsPlainText() throws Exception {
        mvc.perform(get("/api/catalog/magnets/export").param("author", "Autor"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"magnets.txt\""))
                .andExpect(content().string(first + "\n" + second + "\n"));
        mvc.perform(get("/api/catalog/magnets/export").param("title", "missing"))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }
}
