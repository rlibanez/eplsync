package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Transactional
class CatalogMinuteOrderingTests {
    @Autowired CatalogBookRepository books;
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setup() {
        books.deleteAllInBatch();
        String[] times = {"2026-10-04T14:55:59.999Z", "2026-10-04T14:55:00.001Z", "2026-10-04T14:56:00Z", "2026-10-04T14:54:59.999Z"};
        for (int i=0; i<times.length; i++) {
            long id = i+1;
            books.saveAndFlush(CatalogBook.builder().eplId(id).title("Book").author("Author").revision(1.0).links(String.valueOf(id).repeat(40)).build());
            em.createQuery("update CatalogBook b set b.insertDate = :date where b.eplId = :id")
                .setParameter("date", Instant.parse(times[i])).setParameter("id", id).executeUpdate();
        }
        em.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @Test void descendingSortUsesIdWithinMinuteAcrossPagesAndPreservesTimestamps() throws Exception {
        mvc.perform(get("/api/catalog/books").param("page","0").param("size","2").param("sort","insertDate,desc","eplId,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].eplId").value(org.hamcrest.Matchers.contains(3,2)))
            .andExpect(jsonPath("$.items[1].insertDate").value("2026-10-04T14:55:00.001Z"))
            .andExpect(jsonPath("$.items[1].insertMinute").doesNotExist());
        mvc.perform(get("/api/catalog/books").param("page","1").param("size","2").param("sort","insertDate,desc","eplId,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].eplId").value(org.hamcrest.Matchers.contains(1,4)));
    }
    @Test void ascendingSortUsesStableIdTieBreaker() throws Exception {
        mvc.perform(get("/api/catalog/books").param("page","0").param("size","10").param("sort","insertDate,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].eplId").value(org.hamcrest.Matchers.contains(4,1,2,3)));
    }
    @Test void magnetProjectionUsesMinuteOrderingToo() throws Exception {
        mvc.perform(get("/api/catalog/magnets").param("sort","insertDate,desc","eplId,desc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0]", org.hamcrest.Matchers.containsString("dn=EPL_3_")))
            .andExpect(jsonPath("$.items[1]", org.hamcrest.Matchers.containsString("dn=EPL_2_")))
            .andExpect(jsonPath("$.items[2]", org.hamcrest.Matchers.containsString("dn=EPL_1_")));
    }
    @Test void unpagedApiHonorsTheSameOrdering() throws Exception {
        mvc.perform(get("/api/catalog/books").param("sort","insertDate,desc","eplId,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].eplId").value(org.hamcrest.Matchers.contains(3,2,1,4)));
    }
}
