package com.rlibanez.eplsync.model;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

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
        em.persist(book);
        em.flush();
        assertThat(book.getInsertDate()).isEqualTo(LocalDate.now());
        assertThat(book.getLastModifiedDate()).isNull();

        // Simula un alta anterior para detectar si una actualización sobrescribe su fecha.
        em.createNativeQuery("UPDATE catalog_books SET insert_date = :date WHERE epl_id = 1")
                .setParameter("date", LocalDate.of(2025, 1, 1)).executeUpdate();
        em.clear();
        book = em.find(CatalogBook.class, 1L);
        LocalDate originalDate = book.getInsertDate();
        em.detach(book);
        em.merge(book.toBuilder().title("Título actualizado").build());
        em.flush();
        em.clear();

        CatalogBook updated = em.find(CatalogBook.class, 1L);
        assertThat(updated.getInsertDate()).isEqualTo(originalDate);
        assertThat(updated.getLastModifiedDate()).isEqualTo(LocalDate.now());
        assertThat(updated.getPublicationYear()).isEqualTo(1990);
        assertThat(updated.getPublicationDate()).isEqualTo(LocalDate.of(2020, 1, 1));
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
