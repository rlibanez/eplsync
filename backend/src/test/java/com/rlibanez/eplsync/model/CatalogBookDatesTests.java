package com.rlibanez.eplsync.model;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Transactional
class CatalogBookDatesTests {

    @Autowired
    private EntityManager em;

    @Autowired
    private tools.jackson.databind.ObjectMapper mapper;

    @BeforeEach
    void clearCatalog() {
        em.createQuery("delete from CatalogBook").executeUpdate();
        em.clear();
    }

    @Test
    void recordsCreationAndModificationAndPreservesCreationOnMerge() {
        CatalogBook book = CatalogBook.builder()
                .eplId(1L).revision(1.0).author("Autor").title("Título")
                .publicationYear(1990).publicationDate(LocalDate.of(2020, 1, 1))
                .build();
        Instant before = Instant.now().minusSeconds(1);
        em.persist(book);
        em.flush();
        assertThat(book.getInsertDate()).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(book.getLastModifiedDate()).isNull();

        // Simula un alta anterior para detectar si una actualización sobrescribe su fecha.
        em.createNativeQuery("UPDATE catalog_books SET insert_date = :date WHERE epl_id = 1")
                .setParameter("date", Instant.parse("2025-01-01T12:34:56Z")).executeUpdate();
        em.clear();
        book = em.find(CatalogBook.class, 1L);
        Instant originalDate = book.getInsertDate();
        assertThat(originalDate).isEqualTo(Instant.parse("2025-01-01T12:34:56Z"));
        em.detach(book);
        em.merge(book.toBuilder().title("Título actualizado").build());
        em.flush();
        em.clear();

        CatalogBook updated = em.find(CatalogBook.class, 1L);
        assertThat(updated.getInsertDate()).isEqualTo(originalDate);
        assertThat(updated.getLastModifiedDate()).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(updated.getPublicationYear()).isEqualTo(1990);
        assertThat(updated.getPublicationDate()).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    @Test
    void serializesAuditDatesAsUtcInstantsAndPublicationAsDate() {
        var book = CatalogBook.builder().eplId(3L)
                .insertDate(Instant.parse("2026-09-29T07:16:18.123Z"))
                .lastModifiedDate(Instant.parse("2026-09-29T10:42:05.456Z"))
                .publicationDate(LocalDate.of(2026, 9, 27)).build();
        var response = com.rlibanez.eplsync.dto.CatalogBookResponse.from(book, null);
        for (Object value : new Object[]{book, response}) {
            var json = mapper.readTree(mapper.writeValueAsString(value));
            assertThat(json.get("insertDate").stringValue()).isEqualTo("2026-09-29T07:16:18.123Z");
            assertThat(json.get("lastModifiedDate").stringValue()).isEqualTo("2026-09-29T10:42:05.456Z");
            assertThat(json.get("publicationDate").stringValue()).isEqualTo("2026-09-27");
        }
    }

    @Test
    void mergingUnchangedBookDoesNotRecordModification() {
        CatalogBook book = CatalogBook.builder()
                .eplId(2L).revision(1.0).author("Autor").title("Título").build();
        em.persist(book);
        em.flush();
        em.clear();
        em.merge(book.toBuilder().build());
        em.flush();
        em.clear();
        assertThat(em.find(CatalogBook.class, 2L).getLastModifiedDate()).isNull();
    }
}
