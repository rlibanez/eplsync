package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.exception.GlobalExceptionHandler;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "eplsync.torrent.trackers="
})
@Transactional
@WithMockUser(authorities = "CATALOG_READ")
class CatalogDataExportTests {
    @Autowired CatalogDataExportController controller;
    @Autowired CatalogBookRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        repository.save(CatalogBook.builder().eplId(1L).revision(1.0).title("Uno; «ñ»")
                .author("Autor").genres("Aventura, Misterio").build());
        repository.save(CatalogBook.builder().eplId(2L).revision(1.0).title("Dos")
                .author("Autor").build());
        repository.saveAndFlush(CatalogBook.builder().eplId(3L).revision(1.0).title("Tres")
                .author("Otro").build());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void csvUsesFilteredSelectionExclusionsColumnOrderAndSeparator() throws Exception {
        String body = """
                {"filters":{"author":["Autor"],"excludedIds":[2]},"format":"CSV",
                 "separator":"SEMICOLON","columns":["title","eplId"]}
                """;
        String expected = "title;eplId\r\n\"Uno; «ñ»\";1\r\n";
        mvc.perform(post("/api/catalog/data/export/preview").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(1))
                .andExpect(jsonPath("$.text").value(expected));
        export(body).andExpect(header().string("Content-Disposition", "attachment; filename=\"catalogo.csv\""))
                .andExpect(content().string(expected));
    }

    @Test
    void jsonPreservesSelectedBooksAndBothFormatsAllowCustomExtensions() throws Exception {
        export("""
                {"filters":{"selectedIds":[1,3]},"format":"JSON","columns":["eplId","genres"],
                 "ordering":"eplId,desc","extension":"data.json"}
                """).andExpect(header().string("Content-Disposition", "attachment; filename=\"catalogo.data.json\""))
                .andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(content().json("""
                [{"eplId":3,"genres":[]},{"eplId":1,"genres":["Aventura","Misterio"]}]
                """, org.springframework.test.json.JsonCompareMode.STRICT));
        export("""
                {"filters":{"selectedIds":[2]},"format":"CSV","separator":"TAB","extension":"txt","columns":["eplId","title"]}
                """).andExpect(header().string("Content-Disposition", "attachment; filename=\"catalogo.txt\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv")).andExpect(content().string("eplId\ttitle\r\n2\tDos\r\n"));
        export("""
                {"filters":{"selectedIds":[]},"format":"JSON","columns":["eplId"]}
                """).andExpect(content().json("[]"));
    }

    @Test
    void rejectsColumnsOutsideTheCatalog() throws Exception {
        mvc.perform(post("/api/catalog/data/export/preview").contentType("application/json").content("""
                {"filters":{},"format":"CSV","columns":["links"]}
                """)).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "CALIBRE_READ")
    void deniesPreviewWithoutCatalogPermission() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.preview(
                new com.rlibanez.eplsync.service.CatalogDataExport.Request(
                        new com.rlibanez.eplsync.filter.CatalogBookFilter(),
                        com.rlibanez.eplsync.service.CatalogDataExport.Format.CSV, null, java.util.List.of("eplId"), null, null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private org.springframework.test.web.servlet.ResultActions export(String body) throws Exception {
        var result = mvc.perform(post("/api/catalog/data/export").contentType("application/json").content(body))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}
