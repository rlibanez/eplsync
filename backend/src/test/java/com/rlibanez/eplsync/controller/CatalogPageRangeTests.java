package com.rlibanez.eplsync.controller;

import java.util.Objects;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Transactional
class CatalogPageRangeTests {
    @Autowired CatalogBookRepository books;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach void seed() {
        books.deleteAllInBatch();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Integer[] pages = {null, 0, 100, 200, 300};
        for (int i = 0; i < pages.length; i++)
            books.saveAndFlush(CatalogBook.builder().eplId((long)i + 1).title("Book")
                .author("Author").revision(1.0).pages(pages[i]).build());
    }
    @Test void inclusiveAndOpenBoundsExcludeUnknownPageCounts() throws Exception {
        mvc.perform(get("/api/catalog/books").param("pagesFrom","200"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].eplId", org.hamcrest.Matchers.containsInAnyOrder(4,5)));
        mvc.perform(get("/api/catalog/books").param("pagesTo","100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].eplId", org.hamcrest.Matchers.containsInAnyOrder(2,3)));
        mvc.perform(get("/api/catalog/books").param("pagesFrom","100").param("pagesTo","200"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].eplId", org.hamcrest.Matchers.containsInAnyOrder(3,4)));
        mvc.perform(get("/api/catalog/books").param("pagesFrom","200").param("pagesTo","200"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].eplId").value(4)).andExpect(jsonPath("$.length()").value(1));
    }
    @Test void rejectsInvalidRanges() throws Exception {
        mvc.perform(get("/api/catalog/books").param("pagesFrom","200").param("pagesTo","100")).andExpect(status().isBadRequest());
        for (String invalid : new String[]{"-1","1.5","abc","2147483648"})
            mvc.perform(get("/api/catalog/books").param("pagesFrom",invalid)).andExpect(status().isBadRequest());
    }
    @Test void jsonFiltersForJobsAndExportsPreservePageBounds() {
        var filter = tools.jackson.databind.json.JsonMapper.builder().build()
            .readValue("{\"pagesFrom\":100,\"pagesTo\":200,\"eplId\":[3,5]}", CatalogBookFilter.class);
        assertThat(books.findAll(CatalogBookSpecifications.fromFilter(filter)))
            .extracting(value -> Objects.requireNonNull(value).getEplId()).containsExactly(3L);
    }
}
