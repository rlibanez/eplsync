package com.rlibanez.eplsync.controller;

import java.util.Objects;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","TORRENT_SYNC","TORRENT_SEND","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Transactional
class CatalogDirectoryTests {
 @Autowired CatalogDirectoryController controller;
 @Autowired EntityManager em;
 @Test void listsDistinctValuesAndFiltersWithoutExposingArbitraryFields() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  em.persist(CatalogBook.builder().eplId(1L).title("One").author("Author").revision(1.0).language(Language.ESPANOL).genres("Fiction").publicationYear(2000).build());
  em.persist(CatalogBook.builder().eplId(2L).title("Two").author("Author").revision(1.0).language(Language.ESPANOL).genres("Fiction").publicationYear(2020).build());
  em.flush();
  assertThat(controller.list("authors","uth",0,20).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Author");
  assertThat(controller.list("languages","",0,20).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("es");
  assertThat(controller.list("years","",0,20).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("2000","2020");
  assertThat(controller.list("genres","%",0,20).items()).isEmpty();
  assertThat(controller.list("authors","",1,20).items()).isEmpty();
  assertThat(controller.list("authors","",0,1000).items()).hasSize(1);
  assertThatThrownBy(()->controller.list("links","",0,20)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->controller.list("authors","",Integer.MAX_VALUE,500)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void splitsAuthorsBeforeSearchDeduplicationAndPagination() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  em.persist(CatalogBook.builder().eplId(1L).title("Anthology").author(" AA. VV. & Arthur C. Clarke & Luis Vigil (tr) & Arthur C. Clarke && ")
      .revision(1.0).build());
  em.persist(CatalogBook.builder().eplId(2L).title("Other").author("Arthur C. Clarke & Doe, Jane").revision(1.0).build());
  for (int i=0;i<12;i++) em.persist(CatalogBook.builder().eplId(10L+i).title("Book")
      .author(String.format("Writer %02d", i)).revision(1.0).build());
  em.flush();
  var all = controller.list("authors", "", 0, 20);
  assertThat(all.items()).extracting(value -> Objects.requireNonNull(value).value())
      .contains("AA. VV.", "Arthur C. Clarke", "Luis Vigil (tr)", "Doe, Jane")
      .doesNotContain("", "Doe", "Jane");
  assertThat(all.items()).hasSize(16);
  assertThat(controller.list("authors", "clarke", 0, 20).items())
      .extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Arthur C. Clarke");
  assertThat(controller.list("authors", "", 0, 10).items()).hasSize(10);
  assertThat(controller.list("authors", "", 1, 10).items()).hasSize(6);
  assertThat(controller.list("authors", "%", 0, 20).items()).isEmpty();
 }

 @Test void genresAreIndividualAndDeduplicated() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  em.persist(CatalogBook.builder().eplId(1L).title("One").author("Author").revision(1.0)
      .genres("Arqueología, Biología, Ciencias naturales").build());
  em.persist(CatalogBook.builder().eplId(2L).title("Two").author("Author").revision(1.0)
      .genres("Arqueología, Historia, Historia, , Viajes").build());
  em.flush();
  assertThat(controller.list("genres", "", 0, 20).items())
      .extracting(value -> Objects.requireNonNull(value).value())
      .containsExactly("Arqueología", "Biología", "Ciencias naturales", "Historia", "Viajes");
  assertThat(controller.list("genres", "hist", 0, 20).items())
      .extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Historia");
 }

 @Test void collectionsAndInitialsUseNormalizedLettersBeforePagination() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  String[] names = {"Álvaro", "Alberto", "Ñandú", "Nora", "123 libros", "!Especial", "Émile"};
  for (int i=0;i<names.length;i++) em.persist(CatalogBook.builder().eplId(100L+i).title("Book")
      .author(names[i]).collection(names[i]).genres(names[i]).revision(1.0).build());
  em.flush();
  for (String kind : java.util.List.of("authors", "collections", "genres")) {
   assertThat(controller.list(kind,"",0,20,"A").items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Alberto","Álvaro");
   assertThat(controller.list(kind,"alv",0,20,"A").items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Álvaro");
   assertThat(controller.list(kind,"",0,20,"N").items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Nora");
   assertThat(controller.list(kind,"",0,20,"Ñ").items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Ñandú");
   assertThat(controller.list(kind,"",0,20,"E").items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Émile","!Especial");
   assertThat(controller.list(kind,"",0,20,"#").items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("123 libros");
   assertThat(controller.list(kind,"",1,10,"A").items()).isEmpty();
  }
  assertThatThrownBy(() -> controller.list("authors","",0,20,"AB")).isInstanceOf(IllegalArgumentException.class);
 }

 @Test void centuryFilterUsesInclusiveBoundariesBeforePagination() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  int[] years = {-2500, -468, 0, 1, 100, 101, 200, 1900, 1901, 2000, 2001, 2100, 2101};
  for (int i=0; i<years.length; i++) em.persist(CatalogBook.builder().eplId((long)i+1).title("Book").author("Author").revision(1.0).publicationYear(years[i]).build());
  em.flush();
  assertThat(controller.list("years","",0,20,"",0).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("-2500","-468","0");
  assertThat(controller.list("years","",0,20,"",1).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("1","100");
  assertThat(controller.list("years","",0,20,"",20).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("1901","2000");
  assertThat(controller.list("years","",0,20,"",21).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("2001","2100");
  assertThat(controller.list("years","200",0,20,"",21).items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("2001");
  assertThat(controller.list("years","",1,10,"",20).items()).isEmpty();
  assertThat(controller.list("years","",0,20).items()).hasSize(years.length);
  assertThatThrownBy(() -> controller.list("years","",0,20,"",22)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> controller.list("authors","",0,20,"",1)).isInstanceOf(IllegalArgumentException.class);
 }

 @Test void unicodeWhitespaceIsStrippedBeforeDeduplicationAndPaging() {
  em.createQuery("delete from CatalogBook").executeUpdate();
  em.persist(CatalogBook.builder().eplId(1L).title("Book").author("\u2003Álvaro\u2003")
      .collection("\u2003Álvaro\u2003").revision(1.0).build());
  em.persist(CatalogBook.builder().eplId(2L).title("Book").author("Álvaro")
      .collection("Álvaro").revision(1.0).build());
  em.flush();
  for (String kind : java.util.List.of("authors", "collections")) {
   var page = controller.list(kind, "alv", 0, 10, "A");
   assertThat(page.items()).extracting(value -> Objects.requireNonNull(value).value()).containsExactly("Álvaro");
   assertThat(page.meta().totalItems()).isEqualTo(1);
  }
 }

}
