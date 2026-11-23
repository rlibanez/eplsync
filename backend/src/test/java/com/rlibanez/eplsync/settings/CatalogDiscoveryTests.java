package com.rlibanez.eplsync.settings;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.torrent.enabled=false"})
@Transactional
@WithMockUser(authorities={"CATALOG_READ","TORRENT_SYNC","TORRENT_JOBS_MANAGE","CALIBRE_READ","CALIBRE_SEND"})
class CatalogDiscoveryTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogDiscovery discovery;
    @Autowired CatalogBookService catalog;
    @Autowired DownloadTrackingService tracking;
    @Autowired CalibreSettings settings;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void seed() {
        mvc=MockMvcBuilders.webAppContextSetup(context).build();settings.save(new CalibreSettings.Input("http://calibre.test","","",true,"main"));
        String[] authors={"Fríida Ísberg & Autor B","Fríida Ísberg","Autor B","Fríida Ísbergson","Autor C","Autor C","FRÍIDA ÍSBERG","Fríida Ísberg","Dormido","Autor C"};
        for(int i=0;i<authors.length;i++) jdbc.update("INSERT INTO catalog_books(epl_id,title,author,collection,revision,links) VALUES(?,?,?,?,2,?)",i+1,"Libro "+(i+1),authors[i],Set.of(1,2,7,8).contains(i+1)?"Ciclo 1":Set.of(5,6,10).contains(i+1)?"Ciclo C":"Otros",String.format("%040d",i+1));
        for(int id:List.of(1,7,8,9)) jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,1,?,'qbittorrent',?,?,'EPLSYNC',0)","discover-"+id,id,"hash-"+id,id==8?"other-client":tracking.instanceId(),id==9?"NOT_FOUND":id==7?"SUBMITTED":"DOWNLOADED");
        for(int id:List.of(1,5)) jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES(?,'g',1,?,?,1,'Book','Author')","calibre-"+id,id,id);
        jdbc.update("INSERT INTO calibre_books(id,generation,active,calibre_id,epl_id,revision,title,authors) VALUES('inactive','old',0,9,9,1,'Book','Dormido')");
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,state,type,target_fingerprint,batch_size,concurrency,interval_millis,selected_books) VALUES('pending','PAUSED','DOWNLOAD',?,100,1,0,1)",tracking.instanceId());
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,position,epl_id,revision,state,attempts) VALUES('pending-item','pending',0,10,2,'PENDING',0)");
    }
    CatalogBookFilter filter(String source,String criterion) {var f=new CatalogBookFilter();f.setDiscoverySource(source);f.setDiscoveryCriterion(criterion);f.setDiscoveryExcludeDownloads(true);f.setDiscoveryExcludeCalibre(true);f.setDiscoveryExcludePending(true);return f;}
    List<Long> ids(CatalogBookFilter f) {return catalog.search(f,org.springframework.data.domain.PageRequest.of(0,100,org.springframework.data.domain.Sort.by("eplId"))).getContent().stream().map(book->Objects.requireNonNull(book).getEplId()).toList();}
    @Test void wholeAuthorsBothSourcesExcludeExistingOldRevisionsAndPendingWithoutSubstrings() {
        assertThat(ids(filter("BOTH","AUTHOR"))).containsExactly(2L,3L,6L,8L);
        assertThat(ids(filter("DOWNLOADS","AUTHOR"))).containsExactly(2L,3L,8L);
        var values=discovery.options("DOWNLOADS","AUTHOR","",0,100).items();
        assertThat(values).extracting(CatalogDiscovery.Value::label).containsExactly("Autor B","Fríida Ísberg");
        assertThat(values).allSatisfy(value->assertThat(value.counts()).containsEntry("DOWNLOADS",1L));
    }
    @Test void collectionsAndSelectedUnicodeValuesAreBoundedByTheOwnedVocabulary() {
        assertThat(ids(filter("DOWNLOADS","COLLECTION"))).containsExactly(2L,8L);
        var f=filter("DOWNLOADS","AUTHOR");f.setDiscoveryValues(new String[]{java.text.Normalizer.normalize("FRÍIDA ÍSBERG",java.text.Normalizer.Form.NFD)});
        assertThat(ids(f)).containsExactly(2L,8L);
        f.setDiscoveryValues(new String[]{"Fríida Ísbergson"});assertThat(ids(f)).isEmpty();
        assertThat(discovery.options("BOTH","AUTHOR","AUTOR",0,1).meta().totalItems()).isEqualTo(2);
        assertThat(discovery.options("BOTH","AUTHOR","AUTOR",1,1).items()).hasSize(1);
    }
    @Test void exclusionsAreIndependentAndAndWithAdvancedFiltersAndIds() {
        var f=filter("BOTH","AUTHOR");f.setDiscoveryExcludeDownloads(false);f.setDiscoveryExcludeCalibre(false);f.setDiscoveryExcludePending(false);
        assertThat(ids(f)).containsExactly(1L,2L,3L,5L,6L,7L,8L,10L);
        f.setSelectedIds(new Long[]{2L,4L,6L});f.setExcludedIds(new Long[]{6L});assertThat(ids(f)).containsExactly(2L);
        f.setSelectedIds(null);f.setExcludedIds(null);f.setAuthor(new String[]{"Autor C"});f.setAuthorOperator("ne");assertThat(ids(f)).doesNotContain(5L,6L,10L);
    }
    @Test void catalogExplainsDeduplicatedOriginsAndExportKeepsDiscoverySelection() throws Exception {
        mvc.perform(get("/api/catalog/books").param("discoverySource","BOTH").param("discoveryCriterion","AUTHOR").param("discoveryExcludeDownloads","true").param("discoveryExcludeCalibre","true").param("discoveryExcludePending","true").param("sort","eplId,asc").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(4)).andExpect(jsonPath("$.items[0].eplId").value(2)).andExpect(jsonPath("$.items[0].discoveryMatches.length()").value(2));
        mvc.perform(post("/api/catalog/data/export/preview").contentType("application/json").content("""
            {"filters":{"discoverySource":"DOWNLOADS","discoveryCriterion":"COLLECTION","discoveryExcludeDownloads":true,"discoveryExcludeCalibre":true,"discoveryExcludePending":true,"excludedIds":[8]},"format":"JSON","columns":["eplId"]}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(1)).andExpect(result->{var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var body=mapper.readTree(result.getResponse().getContentAsString());assertThat(mapper.readTree(body.path("text").asString()).get(0).path("eplId").asLong()).isEqualTo(2);});
    }
    @Test @WithMockUser(authorities="CATALOG_READ")
    void catalogPermissionAloneCannotInferOwnedValues() throws Exception {
        mvc.perform(get("/api/catalog/discovery/values").param("source","DOWNLOADS")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/books").param("discoverySource","CALIBRE")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(authorities={"CATALOG_READ","CALIBRE_READ"})
    void calibreReadersCannotUseDownloadsAndPendingJobsRemainPrivate() throws Exception {
        var f=filter("CALIBRE","AUTHOR");f.setDiscoveryExcludeDownloads(false);
        assertThat(ids(f)).contains(10L);
        assertThatThrownBy(()->discovery.options("BOTH","AUTHOR","",0,20)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void pendingCalibreUsesCurrentDestinationAndLibraryAndKeepsCompletedItems() {
        String destination=CalibreTransfers.destination(settings.connection());
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('discovery-calibre','now','now','user',?,'main','CATALOG','PAUSED')",destination);
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,name,epl_id,revision,state) VALUES('discovery-calibre-item','discovery-calibre',0,'Book',6,'1','WAITING_DOWNLOAD')");
        assertThat(ids(filter("CALIBRE","AUTHOR"))).doesNotContain(6L);
        jdbc.update("UPDATE calibre_jobs SET library='other' WHERE id='discovery-calibre'");
        assertThat(ids(filter("CALIBRE","AUTHOR"))).contains(6L);
        jdbc.update("UPDATE calibre_jobs SET library='main',destination='other' WHERE id='discovery-calibre'");
        assertThat(ids(filter("CALIBRE","AUTHOR"))).contains(6L);
        jdbc.update("UPDATE calibre_jobs SET destination=?,state='COMPLETED' WHERE id='discovery-calibre'",destination);
        assertThat(ids(filter("CALIBRE","AUTHOR"))).contains(6L);
    }
    @Test void legacyExclusionsMigrateToCanonicalPresenceAndExplicitPresenceWins() {
        var legacy=filter("DOWNLOADS","AUTHOR");
        assertThat(ids(legacy)).containsExactly(2L,3L,8L);
        assertThat(legacy.getTorrentPresence()).isEqualTo("ABSENT");
        assertThat(legacy.getCalibrePresence()).isEqualTo("ABSENT");
        assertThat(legacy.getDiscoveryExcludeDownloads()).isNull();
        var explicit=filter("DOWNLOADS","AUTHOR");explicit.setTorrentPresence("PRESENT");
        assertThat(ids(explicit)).containsExactly(7L);
        assertThat(explicit.getDiscoveryExcludeDownloads()).isNull();
    }
    void seedMetadata() {
        jdbc.update("UPDATE catalog_books SET genres='Fantasía, Aventura',language='ESPANOL',publication_year=2000 WHERE epl_id=1");
        jdbc.update("UPDATE catalog_books SET genres='Fantasía',language='ESPANOL',publication_year=2000 WHERE epl_id IN (2,4)");
        jdbc.update("UPDATE catalog_books SET genres='Terror',language='INGLES',publication_year=2020 WHERE epl_id=3");
        jdbc.update("UPDATE catalog_books SET genres='Drama',language='INGLES',publication_year=2010 WHERE epl_id IN (5,6,10)");
        jdbc.update("UPDATE catalog_books SET genres='Fantasía',language='ESPANOL',publication_year=0 WHERE epl_id=8");
        jdbc.update("UPDATE catalog_books SET genres='Aventura' WHERE epl_id=9");
    }
    @Test void genresLanguagesAndYearsMatchWholeTokensAndIgnoreUnknownYears() {
        seedMetadata();
        assertThat(ids(filter("DOWNLOADS","GENRE"))).containsExactly(2L,4L,8L,9L);
        assertThat(ids(filter("DOWNLOADS","LANGUAGE"))).containsExactly(2L,4L,8L);
        assertThat(ids(filter("DOWNLOADS","YEAR"))).containsExactly(2L,4L);
        assertThat(discovery.options("DOWNLOADS","GENRE","",0,100).items()).extracting(CatalogDiscovery.Value::label).containsExactly("Aventura","Fantasía");
        assertThat(discovery.options("DOWNLOADS","LANGUAGE","",0,100).items()).extracting(CatalogDiscovery.Value::value).containsExactly("es");
        assertThat(discovery.options("BOTH","YEAR","",0,100).items()).extracting(CatalogDiscovery.Value::value).containsExactly("2000","2010");
        jdbc.update("UPDATE catalog_books SET genres='Fantasía épica' WHERE epl_id=4");
        assertThat(ids(filter("DOWNLOADS","GENRE"))).doesNotContain(4L);
    }
    @Test void allAndAnyCombineCriteriaWhileAlwaysApplyingPresenceAndPendingExclusions() {
        seedMetadata();
        var f=filter("DOWNLOADS","AUTHOR");f.setDiscoveryCriteria(new String[]{"AUTHOR","GENRE","LANGUAGE","YEAR"});
        f.setDiscoveryMatch("ALL");assertThat(ids(f)).containsExactly(2L);
        f.setDiscoveryMatch("ANY");assertThat(ids(f)).containsExactly(2L,3L,4L,8L,9L);
        var both=filter("BOTH","AUTHOR");both.setDiscoveryCriteria(new String[]{"AUTHOR","COLLECTION","GENRE","LANGUAGE","YEAR"});
        assertThat(ids(both)).containsExactly(2L,6L);
        // Empty vocabulary for one selected criterion makes ALL empty, but does not erase ANY matches.
        f.setDiscoveryValues(new String[]{"GENRE:Drama"});f.setDiscoveryMatch("ALL");assertThat(ids(f)).isEmpty();
        f.setDiscoveryMatch("ANY");assertThat(ids(f)).containsExactly(2L,3L,4L,8L);
        // Selected values are scoped to their own criterion and cannot introduce values absent from the source.
        f.setDiscoveryCriteria(new String[]{"GENRE","LANGUAGE"});f.setDiscoveryValues(new String[]{"GENRE:Fantasía","LANGUAGE:en"});
        f.setDiscoveryMatch("ALL");assertThat(ids(f)).isEmpty();
        f.setDiscoveryMatch("ANY");assertThat(ids(f)).containsExactly(2L,4L,8L);
    }
    @Test void combinedApiExplainsEachCriterionAndExportsTheSameDeduplicatedSelection() throws Exception {
        seedMetadata();
        mvc.perform(get("/api/catalog/books").param("discoverySource","DOWNLOADS").param("discoveryCriteria","GENRE","LANGUAGE","YEAR").param("discoveryMatch","ALL").param("torrentPresence","ABSENT").param("calibrePresence","ABSENT").param("discoveryExcludePending","true").param("sort","eplId,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2)).andExpect(jsonPath("$.items[0].eplId").value(2)).andExpect(jsonPath("$.items[0].discoveryMatches.length()").value(3)).andExpect(jsonPath("$.items[0].discoveryMatches[0].criterion").value("GENRE")).andExpect(jsonPath("$.items[0].discoveryMatches[1].value").value("es"));
        mvc.perform(post("/api/catalog/data/export/preview").contentType("application/json").content("""
            {"filters":{"discoverySource":"DOWNLOADS","discoveryCriteria":["AUTHOR","GENRE","LANGUAGE","YEAR"],"discoveryMatch":"ANY","torrentPresence":"ABSENT","calibrePresence":"ABSENT","discoveryExcludePending":true,"excludedIds":[9]},"format":"JSON","columns":["eplId"]}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(4));
    }
    @Test void rejectsInvalidCombinationsAndUnscopedMultiCriterionValues() {
        var f=filter("DOWNLOADS","AUTHOR");f.setDiscoveryCriteria(new String[]{"AUTHOR","INVALID"});assertThatThrownBy(f::normalize).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        f.setDiscoveryCriteria(new String[]{});assertThatThrownBy(f::normalize).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        f.setDiscoveryCriteria(new String[]{"AUTHOR","GENRE"});f.setDiscoveryValues(new String[]{"Fríida Ísberg"});assertThatThrownBy(f::normalize).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        f.setDiscoveryValues(new String[]{"YEAR:2000"});assertThatThrownBy(f::normalize).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        f.setDiscoveryValues(null);f.setDiscoveryMatch("INVALID");assertThatThrownBy(f::normalize).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    @Test void validatesAllCriteriaAndEmptyLibraries() {
        var criterion=filter("DOWNLOADS","INVALID");assertThatThrownBy(()->ids(criterion)).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        jdbc.update("UPDATE torrent_downloads SET status='NOT_FOUND'");assertThat(ids(filter("DOWNLOADS","AUTHOR"))).isEmpty();
        var f=filter("DOWNLOADS","AUTHOR");f.setDiscoveryValues(new String[]{});var invalid=f;assertThatThrownBy(invalid::normalize).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
    }
    @Test void authorDetectionIgnoresAnthologiesAndBooksAboveTheLimitWithoutExcludingResults() throws Exception {
        jdbc.update("UPDATE catalog_books SET author='AA. VV. & Compartido & Solo antología' WHERE epl_id=1");
        jdbc.update("UPDATE catalog_books SET author='Compartido & Otro' WHERE epl_id=5");
        jdbc.update("UPDATE catalog_books SET author='Solo antología' WHERE epl_id=2");
        jdbc.update("UPDATE catalog_books SET author='AA. VV. & Compartido & Solo antología' WHERE epl_id=4");
        assertThat(discovery.options("CALIBRE","AUTHOR","",0,100).meta().totalItems()).isEqualTo(4);
        assertThat(discovery.options("CALIBRE","AUTHOR","",0,100,true,null).items()).extracting(CatalogDiscovery.Value::label).containsExactly("Compartido","Otro");
        assertThat(discovery.options("DOWNLOADS","AUTHOR","",0,100,true,null).items()).isEmpty();
        assertThat(discovery.options("BOTH","AUTHOR","",0,100,true,2).items()).extracting(CatalogDiscovery.Value::label).containsExactly("Compartido","Otro");
        assertThat(discovery.options("CALIBRE","AUTHOR","",0,100,false,1).items()).isEmpty();
        assertThat(discovery.options("CALIBRE","AUTHOR","",0,100,false,2).items()).extracting(CatalogDiscovery.Value::label).containsExactly("Compartido","Otro");
        assertThat(discovery.options("CALIBRE","AUTHOR","",0,100,false,3).meta().totalItems()).isEqualTo(4);
        var f=filter("CALIBRE","AUTHOR");f.setDiscoveryIgnoreAnthologies(true);f.setDiscoveryMaxAuthors(2);
        assertThat(ids(f)).contains(4L).doesNotContain(2L);
        f.setDiscoveryValues(new String[]{"Solo antología"});assertThat(ids(f)).isEmpty();
        assertThat(discovery.options("CALIBRE","COLLECTION","",0,100,true,1)).isEqualTo(discovery.options("CALIBRE","COLLECTION","",0,100));
        mvc.perform(get("/api/catalog/discovery/values").param("source","CALIBRE").param("maxAuthors","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books").param("discoverySource","CALIBRE").param("discoveryMaxAuthors","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/discovery/values").param("source","CALIBRE").param("ignoreAnthologies","true").param("maxAuthors","2")).andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
    }

    @Test @WithMockUser(authorities="CATALOG_READ")
    void catalogAuthorshipExcludesResultsIndependentlyOfDiscoveryAndExportsTheSameSelection() throws Exception {
        jdbc.update("UPDATE catalog_books SET author=CASE epl_id WHEN 1 THEN 'AA. VV. & Uno' WHEN 2 THEN 'Solo' WHEN 3 THEN 'Uno & Dos' WHEN 4 THEN 'Uno & Dos & Tres' WHEN 5 THEN 'Sin autor conocido' WHEN 6 THEN 'Uno & UNO' WHEN 7 THEN 'Saa. VV. autor' WHEN 8 THEN ' aa. vv. & Otro' ELSE author END");
        var f=new CatalogBookFilter();
        f.setExcludeAnthologies(true);assertThat(ids(f)).doesNotContain(1L,8L).hasSize(8);
        f.setExcludeAnthologies(false);f.setMaxAuthors(1);assertThat(ids(f)).containsExactly(2L,5L,6L,7L,9L,10L);
        f.setExcludeAnthologies(true);f.setMaxAuthors(2);f.setAuthor(new String[]{"Uno"});assertThat(ids(f)).containsExactly(3L,6L);
        mvc.perform(get("/api/catalog/books").param("excludeAnthologies","true").param("maxAuthors","2").param("author","Uno")).andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
        mvc.perform(post("/api/catalog/data/export/preview").contentType("application/json").content("""
            {"filters":{"excludeAnthologies":true,"maxAuthors":2,"author":["Uno"]},"format":"JSON","columns":["eplId"]}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(2));
        mvc.perform(get("/api/catalog/books").param("maxAuthors","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/books").param("maxAuthors","-1")).andExpect(status().isBadRequest());
    }

    @Test void authorSummaryDeduplicatesSourcesAndExplainsOverlappingExclusions() throws Exception {
        jdbc.update("UPDATE catalog_books SET author='AA. VV. & Uno & Dos' WHERE epl_id=1");
        jdbc.update("UPDATE catalog_books SET author='Uno & Otro' WHERE epl_id=5");
        assertThat(discovery.authorSummary("BOTH",true,2)).isEqualTo(new CatalogDiscovery.AuthorSummary(2,1,1,1,1));
        assertThat(discovery.authorSummary("BOTH",true,1)).isEqualTo(new CatalogDiscovery.AuthorSummary(2,2,1,2,1));
        assertThat(discovery.authorSummary("BOTH",false,null)).isEqualTo(new CatalogDiscovery.AuthorSummary(2,0,0,0,0));
        assertThat(discovery.authorSummary("DOWNLOADS",true,2)).isEqualTo(new CatalogDiscovery.AuthorSummary(1,1,1,1,1));
        assertThat(discovery.authorSummary("CALIBRE",true,2)).isEqualTo(new CatalogDiscovery.AuthorSummary(2,1,1,1,1));
        mvc.perform(get("/api/catalog/discovery/authors/summary").param("source","BOTH").param("ignoreAnthologies","true").param("maxAuthors","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalBooks").value(2)).andExpect(jsonPath("$.ignoredBooks").value(1));
        mvc.perform(get("/api/catalog/discovery/authors/summary").param("source","CALIBRE").param("maxAuthors","0")).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(authorities="CATALOG_READ")
    void authorSummaryRequiresPermissionForEveryRequestedSource() throws Exception {
        mvc.perform(get("/api/catalog/discovery/authors/summary").param("source","DOWNLOADS")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/discovery/authors/summary").param("source","CALIBRE")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/discovery/authors/summary").param("source","BOTH")).andExpect(status().isForbidden());
    }

    @Test void tableSelectionsSupportAllExceptExplicitValuesEmptySelectionAndExport() throws Exception {
        var f=filter("BOTH","AUTHOR");f.setDiscoveryExcludedValues(new String[]{"AUTHOR:FRÍIDA ÍSBERG"});
        assertThat(ids(f)).containsExactly(3L,6L);
        f.setDiscoveryValues(new String[]{"AUTHOR:"});assertThat(ids(f)).isEmpty();
        f.setDiscoveryValues(null);f.setDiscoveryCriteria(new String[]{"AUTHOR","COLLECTION"});f.setDiscoveryMatch("ANY");assertThat(ids(f)).contains(2L,3L,6L,8L);
        mvc.perform(get("/api/catalog/books").param("discoverySource","BOTH").param("discoveryCriteria","AUTHOR").param("discoveryExcludedValues","AUTHOR:Fríida Ísberg").param("torrentPresence","ABSENT").param("calibrePresence","ABSENT").param("discoveryExcludePending","true")).andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(2));
        mvc.perform(post("/api/catalog/data/export/preview").contentType("application/json").content("""
            {"filters":{"discoverySource":"BOTH","discoveryCriteria":["AUTHOR"],"discoveryExcludedValues":["AUTHOR:Fríida Ísberg"],"torrentPresence":"ABSENT","calibrePresence":"ABSENT","discoveryExcludePending":true},"format":"JSON","columns":["eplId"]}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(2));
        mvc.perform(get("/api/catalog/books").param("discoverySource","BOTH").param("discoveryValues","AUTHOR:")).andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalItems").value(0));
        mvc.perform(get("/api/catalog/books").param("discoverySource","BOTH").param("discoveryExcludedValues","GENRE:Other")).andExpect(status().isBadRequest());
    }

    @Test void selectedCountsIgnoreStaleValuesAndDoNotDependOnTextSearchOrPaging() throws Exception {
        var all=discovery.optionsWithSelection("BOTH","AUTHOR","",0,1,false,null,true,null,List.of("Desconocido"));
        assertThat(all.selectedTotal()).isEqualTo(3);assertThat(all.items()).hasSize(1);
        assertThat(discovery.optionsWithSelection("BOTH","AUTHOR","zzzz",0,1,false,null,true,null,List.of("Autor B","Desconocido")).selectedTotal()).isEqualTo(2);
        assertThat(discovery.optionsWithSelection("BOTH","AUTHOR","",0,100,false,null,false,List.of("FRÍIDA ÍSBERG","Desconocido","Fríida Ísberg"),null).selectedTotal()).isEqualTo(1);
        assertThat(discovery.optionsWithSelection("BOTH","AUTHOR","",0,100,false,null,false,null,null).selectedTotal()).isZero();
        jdbc.update("UPDATE catalog_books SET collection='Ciclo, 1' WHERE epl_id=1");
        mvc.perform(get("/api/catalog/discovery/values").param("source","BOTH").param("criterion","COLLECTION").param("allSelected","false").param("selected","Ciclo, 1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.selectedTotal").value(1));
        mvc.perform(get("/api/catalog/discovery/values").param("source","BOTH").param("excluded"," ")).andExpect(status().isBadRequest());
    }

}
