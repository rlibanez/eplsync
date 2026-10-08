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

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "eplsync.torrent.trackers="
})
@Transactional
class CatalogMagnetControllerTests {
    @Autowired com.rlibanez.eplsync.service.MagnetExportService exports;
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
        mvc = MockMvcBuilders.standaloneSetup(new CatalogMagnetController(service, exports))
                .setCustomArgumentResolvers(new SortHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void selectedExportIncludesAllHashesAndDeduplicatesWithoutRequiringAnExtension() throws Exception {
        export(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/catalog/magnets/export")
            .contentType("application/json").content("{\"filters\":{\"selectedIds\":[1,2]}}"))
            .andExpect(status().isOk()).andExpect(content().string(first + "\n" + second + "\n"));
        export(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/catalog/magnets/export")
            .contentType("application/json").content("{\"filters\":{\"selectedIds\":[]}}"))
            .andExpect(status().isOk()).andExpect(content().string(""));
        export(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/catalog/magnets/export")
            .contentType("application/json").content("{\"filters\":{\"author\":[\"Autor\"],\"excludedIds\":[1]}}"))
            .andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Autowired jakarta.persistence.EntityManager em;

    @Test void temporaryDeduplicationIsCleanedOnSuccessAndFailureWithoutLoadingEntities() {
        em.clear();
        var page = service.page(new com.rlibanez.eplsync.filter.CatalogBookFilter(), org.springframework.data.domain.Sort.unsorted(), 0, 1);
        org.assertj.core.api.Assertions.assertThat(page.items()).containsExactly(first);
        org.assertj.core.api.Assertions.assertThat(em.unwrap(org.hibernate.engine.spi.SessionImplementor.class)
                .getPersistenceContext().getNumberOfManagedEntities()).isZero();
        assertNoTemporaryMagnetTables();
        var failingBuilder = org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.MagnetLinkBuilder.class);
        org.mockito.Mockito.when(failingBuilder.hashes(org.mockito.Mockito.any())).thenReturn(java.util.List.of("A".repeat(40)));
        org.mockito.Mockito.when(failingBuilder.build(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
                .thenThrow(new IllegalStateException("Simulated output failure"));
        var failingService = new CatalogMagnetService(repository, failingBuilder);
        org.springframework.test.util.ReflectionTestUtils.setField(failingService, "em", em);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failingService.page(
                new com.rlibanez.eplsync.filter.CatalogBookFilter(), org.springframework.data.domain.Sort.unsorted(), 0, 1))
                .isInstanceOf(IllegalStateException.class);
        assertNoTemporaryMagnetTables();
    }
    private void assertNoTemporaryMagnetTables() {
        org.assertj.core.api.Assertions.assertThat(((Number) em.createNativeQuery(
                "SELECT count(*) FROM sqlite_temp_master WHERE name LIKE 'magnet_page_%'").getSingleResult()).longValue()).isZero();
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
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0]").value(first));
        mvc.perform(get("/api/catalog/magnets").param("author", "Otro"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0]").value(first.replace("EPL_1_Uno", "EPL_2_Dos")));
        mvc.perform(get("/api/catalog/magnets").param("sort", "title,asc"))
                .andExpect(jsonPath("$.items[0]").value(first.replace("EPL_1_Uno", "EPL_2_Dos")));
    }

    @Test
    void paginatesMagnetsAfterExpansionAndDeduplication() throws Exception {
        mvc.perform(get("/api/catalog/magnets").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0]").value(second))
                .andExpect(jsonPath("$.meta.totalItems").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mvc.perform(get("/api/catalog/magnets").param("page", "2147483647").param("size", "500"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/magnets").param("size", "1"))
                .andExpect(jsonPath("$.meta.page").value(0));
        mvc.perform(get("/api/catalog/magnets").param("page", "0"))
                .andExpect(jsonPath("$.meta.size").value(20));
        for (String size : new String[] {"0", "-1", "invalid"}) {
            mvc.perform(get("/api/catalog/magnets").param("size", size)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/catalog/magnets").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/magnets").param("publicationYear", "-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void busyExportReturnsReadableJsonEvenWhenTheClientRequestsPlainText() throws Exception {
        try (var prepared=exports.prepare(new com.rlibanez.eplsync.filter.CatalogBookFilter(), org.springframework.data.domain.Sort.unsorted())) {
            org.assertj.core.api.Assertions.assertThat(prepared).isNotNull();
            mvc.perform(get("/api/catalog/magnets/export").accept("text/plain"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.details").value("Ya hay una exportación en curso. Espera a que termine e inténtalo de nuevo."));
        }
    }
    @Test void invalidExportInputsRemainReadableWithPlainTextAccept() throws Exception {
        mvc.perform(get("/api/catalog/magnets/export").param("sort","unknown,asc").accept("text/plain"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.details").value("Campo de ordenación del catálogo inválido"));
    }
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Test void configuredExecutorStreamsThroughTheApplicationContext() throws Exception {
        var realMvc=MockMvcBuilders.webAppContextSetup(webContext).build();
        var result=realMvc.perform(get("/api/catalog/magnets/export").param("author","Autor"))
                .andExpect(request().asyncStarted()).andReturn();
        realMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(first+"\n"+second+"\n"));
    }

    @Autowired com.rlibanez.eplsync.config.QueryConcurrencyConfiguration concurrency;
    @Test void applicationRejectsExcessQueriesAndRecoversAfterSlotsAreReleased() throws Exception {
        var gate=concurrency.boundedQueries();
        var requests=new java.util.ArrayList<org.springframework.mock.web.MockHttpServletRequest>();
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var realMvc=MockMvcBuilders.webAppContextSetup(webContext).build();
        try {
            for(int i=0;i<4;i++) {
                var request=new org.springframework.mock.web.MockHttpServletRequest(); requests.add(request);
                gate.preHandle(request,response,new Object());
            }
            realMvc.perform(get("/api/catalog/books")).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After","1"))
                    .andExpect(jsonPath("$.details").value("Hay demasiadas consultas en curso. Inténtalo de nuevo en unos segundos."));
        } finally { for(var request:requests) gate.afterCompletion(request,response,new Object(),null); }
        realMvc.perform(get("/api/catalog/books")).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions export(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result));
    }

    @Test
    void exportsFilteredNewlineSeparatedMagnetsAsPlainText() throws Exception {
        export(get("/api/catalog/magnets/export").param("author", "Autor"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"magnets.txt\""))
                .andExpect(content().string(first + "\n" + second + "\n"));
        export(get("/api/catalog/magnets/export").param("title", "missing"))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }
}
