package com.rlibanez.eplsync.controller;

import java.util.Objects;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Transactional
class CatalogDateRangeTests {
 @Autowired CatalogBookRepository repository;
 @Autowired jakarta.persistence.EntityManager em;
 @Test void includesStartAndExcludesNextMidnight() {
   repository.deleteAllInBatch();
   String[] times={"2026-03-28T22:59:59Z","2026-03-28T23:00:00Z","2026-03-29T21:59:59Z","2026-03-29T22:00:00Z"};
   for(int i=0;i<times.length;i++) repository.saveAndFlush(CatalogBook.builder().eplId((long)i+1).title("Book").author("Author").revision(1.0).insertDate(Instant.parse(times[i])).build());
   for(int i=0;i<times.length;i++) em.createQuery("update CatalogBook b set b.insertDate = :date where b.eplId = :id")
       .setParameter("date",Instant.parse(times[i])).setParameter("id",(long)i+1).executeUpdate();
   em.clear();
   var filter=new CatalogBookFilter();
   filter.setInsertDateFrom(Instant.parse(times[1])); filter.setInsertDateBefore(Instant.parse(times[3]));
   assertThat(repository.findAll(CatalogBookSpecifications.fromFilter(filter))).extracting(value -> Objects.requireNonNull(value).getEplId()).containsExactlyInAnyOrder(2L,3L);
 }
 @Test void rejectsInvertedRanges() {
   var f=new CatalogBookFilter(); f.setPublicationDateFrom(LocalDate.of(2026,2,2)); f.setPublicationDateTo(LocalDate.of(2026,2,1));
   assertThatThrownBy(()->CatalogBookSpecifications.fromFilter(f)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void acceptsNegativeYearsAndInclusiveRanges() {
   repository.deleteAllInBatch();
   int[] years = {-2500, -2100, -468, 2000};
   for (int i=0; i<years.length; i++)
     repository.saveAndFlush(CatalogBook.builder().eplId((long)i+1).title("Book").author("Author").revision(1.0).publicationYear(years[i]).build());
   var filter = new CatalogBookFilter();
   filter.setPublicationYear(-2500);
   assertThat(repository.findAll(CatalogBookSpecifications.fromFilter(filter))).extracting(CatalogBook::getEplId).containsExactly(1L);
   filter.setPublicationYear(null); filter.setPublicationYearFrom(-2100); filter.setPublicationYearTo(-468);
   assertThat(repository.findAll(CatalogBookSpecifications.fromFilter(filter))).extracting(CatalogBook::getEplId).containsExactlyInAnyOrder(2L,3L);
   try (var validator = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
     filter.setPublicationYear(-2500);
     assertThat(validator.getValidator().validate(filter)).isEmpty();
   }
 }

}
