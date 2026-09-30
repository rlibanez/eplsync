package com.rlibanez.eplsync.controller;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Transactional
class CatalogDirectoryTests {
 @Autowired CatalogDirectoryController controller;
 @Autowired EntityManager em;
 @Test void listsDistinctValuesAndFiltersWithoutExposingArbitraryFields() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  em.persist(CatalogBook.builder().eplId(1L).title("One").author("Author").revision(1.0).language(Language.ESPANOL).genres("Fiction").publicationYear(2000).build());
  em.persist(CatalogBook.builder().eplId(2L).title("Two").author("Author").revision(1.0).language(Language.ESPANOL).genres("Fiction").publicationYear(2020).build());
  em.flush();
  assertThat(controller.list("authors","uth",0,20).items()).extracting(CatalogDirectoryController.Entry::value).containsExactly("Author");
  assertThat(controller.list("languages","",0,20).items()).extracting(CatalogDirectoryController.Entry::value).containsExactly("es");
  assertThat(controller.list("years","",0,20).items()).extracting(CatalogDirectoryController.Entry::value).containsExactly("2020","2000");
  assertThat(controller.list("genres","%",0,20).items()).isEmpty();
  assertThat(controller.list("authors","",1,20).items()).isEmpty();
  assertThat(controller.list("authors","",0,1000).items()).hasSize(1);
  assertThatThrownBy(()->controller.list("links","",0,20)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->controller.list("authors","",Integer.MAX_VALUE,500)).isInstanceOf(IllegalArgumentException.class);
 }
}
