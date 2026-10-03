package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.*;
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
class CatalogMultiFilterTests {
 @Autowired CatalogBookRepository books;
 @Autowired org.springframework.web.context.WebApplicationContext context;
 MockMvc mvc;
 @BeforeEach void seed() {
  books.deleteAllInBatch(); mvc=MockMvcBuilders.webAppContextSetup(context).build();
  String[] authors={"Isaac Asimov","Brandon Sanderson","Isaac Asimov","Frank Herbert","García Márquez, Gabriel"};
  Language[] langs={Language.ESPANOL,Language.INGLES,Language.FRANCES,Language.ESPANOL,Language.ESPANOL};
  for(int i=0;i<authors.length;i++) books.saveAndFlush(CatalogBook.builder().eplId((long)i+1).title("Book "+i).author(authors[i])
   .language(langs[i]).revision(i==0?1.4:2.0).status(i==1?BookStatus.VERIFICADO:BookStatus.DISPONIBLE)
   .publicationStatus(i==1?PublicationStatus.UPDATED:PublicationStatus.PUBLISHED).genres("Drama").collection("Series").build());
 }
 @Test void repeatedQueryValuesUseOrWithinFieldsAndAndAcrossFields() throws Exception {
  mvc.perform(get("/api/catalog/books").param("author","Asimov","Sanderson").param("language","es","en")
   .param("status","DISPONIBLE","VERIFICADO").param("publicationStatus","PUBLISHED","UPDATED"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
   .andExpect(jsonPath("$[*].eplId",org.hamcrest.Matchers.containsInAnyOrder(1,2)));
  mvc.perform(get("/api/catalog/books").param("eplId","1","2","4").param("revision","1.4","3.0"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].eplId").value(1));
 }
 @Test void commaInSingleTextValueIsNotSplit() throws Exception {
  mvc.perform(get("/api/catalog/books").param("author","García Márquez, Gabriel"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].eplId").value(5));
  mvc.perform(get("/api/catalog/books").param("author","Nobody, Asimov"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
 }
 @Test void jsonSupportsArraysAndSingleValuesWithoutLosingOtherFilters() {
  var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
  var filter=mapper.readValue("""
   {"author":["Asimov","Sanderson"],"eplId":[1,2,4],"revision":[1.4,2.0],"collection":"Series","genres":["Drama","Fantasy"]}
   """,CatalogBookFilter.class);
  assertThat(books.findAll(CatalogBookSpecifications.fromFilter(filter))).extracting(CatalogBook::getEplId).containsExactlyInAnyOrder(1L,2L);
 }
 @Test void invalidValuesAreRejectedRatherThanIgnored() throws Exception {
  mvc.perform(get("/api/catalog/books").param("eplId","1","-2")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/catalog/books").param("revision","1.4","NaN")).andExpect(status().isBadRequest());
 }
}
