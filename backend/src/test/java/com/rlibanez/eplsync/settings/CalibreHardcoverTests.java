package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CalibreHardcoverTests {
    @TempDir Path root;
    CalibreHardcover service;HardcoverClient hardcover;CalibreClient calibre;CalibreSettings settings;SessionAccess sessions;JdbcTemplate jdbc;
    CalibreSettings.Connection connection=new CalibreSettings.Connection("http://calibre.test","","","main");
    CalibreClient.IdentifierMetadata metadata=new CalibreClient.IdentifierMetadata("uuid","Book",List.of("Author"),Map.of("epl","123","revision","1.2","unrelated","keep"));
    @BeforeEach void setup() {
        var source=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("test.db")));
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();jdbc=new JdbcTemplate(source);
        hardcover=mock(HardcoverClient.class);calibre=mock(CalibreClient.class);settings=mock(CalibreSettings.class);sessions=mock(SessionAccess.class);
        var transfers=mock(CalibreTransfers.class);var catalog=mock(CalibreCatalogService.class);
        when(transfers.exclusive(any())).thenAnswer(c->c.<java.util.function.Supplier<Object>>getArgument(0).get());when(catalog.exclusive(any())).thenAnswer(c->c.<java.util.function.Supplier<Object>>getArgument(0).get());
        service=new CalibreHardcover(jdbc,new DataSourceTransactionManager(source),hardcover,calibre,settings,transfers,catalog,sessions);
        when(settings.connection()).thenReturn(connection);when(calibre.identifierMetadata(connection,9)).thenReturn(metadata);when(hardcover.configured()).thenReturn(true);
        var match=new HardcoverClient.Match(7,"book-slug","Book",List.of("Author"));when(hardcover.search("Book",List.of("Author"),1)).thenReturn(new HardcoverClient.Search(List.of(match),false));when(hardcover.book(7)).thenReturn(match);
        when(hardcover.edition(7,11)).thenReturn(new HardcoverClient.Edition(11,"Book","es","Publisher","020161622X","9780201616224","B000000001"));
        owner("owner");
    }
    void owner(String id) {
        var a=new Account(id,id,"","ADMIN","ACTIVE",false,null,0,Permission.defaults("ADMIN"),java.time.Instant.now());when(sessions.current()).thenReturn(a);
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(a,null,a.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    @Test void storesPersonalMappingsWithoutAnyApiKey() {
        service.configure(Map.of(CalibreHardcover.Field.BOOK_ID,"custom_book"));assertThat(service.configuration().mappings()).containsEntry(CalibreHardcover.Field.BOOK_ID,"custom_book");
        owner("other");assertThat(service.configuration().mappings()).containsEntry(CalibreHardcover.Field.BOOK_ID,"hardcover_book");
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key='hardcover.mapping.owner'",String.class)).doesNotContain("api-key","secret");
    }
    @Test void validatesNamesAndProtectsEplRevisionAndUniqueDestinations() {
        for(String name:List.of("epl","revision","uuid","calibre","UPPER","bad name","a:b")) assertThatThrownBy(()->service.configure(Map.of(CalibreHardcover.Field.BOOK_ID,name))).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        assertThatThrownBy(()->service.configure(Map.of(CalibreHardcover.Field.SLUG,"same",CalibreHardcover.Field.BOOK_ID,"same"))).hasMessageContaining("distinto");
    }
    @Test void previewsAndAppliesOnlyConfirmedIdentifiersOnTheSameEntry() {
        var search=service.search(9,null,1);var preview=service.preview(search.token(),7,11L,Map.of(CalibreHardcover.Field.SLUG,"hardcover",CalibreHardcover.Field.BOOK_ID,"hardcover_book",CalibreHardcover.Field.EDITION_ID,"hardcover_edition",CalibreHardcover.Field.ISBN13,"isbn"));
        assertThat(preview.values()).hasSize(4).allSatisfy(v->assertThat(v.state()).isEqualTo("READY"));assertThat(service.apply(preview.token()).saved()).isTrue();
        verify(calibre).addIdentifiers(connection,9,metadata,Map.of("hardcover","book-slug","hardcover_book","7","hardcover_edition","11","isbn","9780201616224"));verify(calibre,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void doesNotInventAnEditionAndPreservesConflictingIdentifiers() {
        metadata=new CalibreClient.IdentifierMetadata("uuid","Book",List.of("Author"),Map.of("hardcover","different"));when(calibre.identifierMetadata(connection,9)).thenReturn(metadata);
        var search=service.search(9,null,1);var p=service.preview(search.token(),7,null,Map.of(CalibreHardcover.Field.SLUG,"hardcover",CalibreHardcover.Field.EDITION_ID,"hardcover_edition",CalibreHardcover.Field.ISBN13,"isbn"));
        assertThat(p.values()).extracting(v->v.state()).containsExactly("CONFLICT","MISSING","MISSING");service.apply(p.token());verify(calibre).addIdentifiers(connection,9,metadata,Map.of());verify(hardcover,never()).edition(anyLong(),anyLong());
    }
    @Test void requiresTheSameOwnerConnectionAndMetadataAtConfirmation() {
        var s=service.search(9,null,1);var p=service.preview(s.token(),7,null,Map.of(CalibreHardcover.Field.BOOK_ID,"hardcover_book"));
        owner("other");assertThatThrownBy(()->service.apply(p.token())).hasMessageContaining("usuario");owner("owner");when(settings.connection()).thenReturn(new CalibreSettings.Connection("http://other.test","","","main"));assertThatThrownBy(()->service.apply(p.token())).hasMessageContaining("cambió");verify(calibre,never()).addIdentifiers(any(),anyLong(),any(),any());
    }
    @Test void rejectsMetadataChangesAndUnselectedBooks() {
        var s=service.search(9,null,1);assertThatThrownBy(()->service.preview(s.token(),8,null,Map.of(CalibreHardcover.Field.BOOK_ID,"hc"))).hasMessageContaining("resultados");
        when(calibre.identifierMetadata(connection,9)).thenReturn(new CalibreClient.IdentifierMetadata("other","Book",List.of("Author"),Map.of()));
        assertThatThrownBy(()->service.preview(s.token(),7,null,Map.of(CalibreHardcover.Field.BOOK_ID,"hc"))).hasMessageContaining("cambiaron");
    }
    @Test void permissionIsRequiredBeforeReadingCalibreOrWritingMappings() {
        SecurityContextHolder.clearContext();assertThatThrownBy(()->service.search(9,null,1)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(calibre);
        assertThatThrownBy(()->service.configure(Map.of(CalibreHardcover.Field.BOOK_ID,"hc"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void validatesIsbnChecksumsAndAsinWithoutLosingLeadingZeroes() {
        assertThat(CalibreHardcover.validValue(CalibreHardcover.Field.ISBN10,"020161622X")).isTrue();
        assertThat(CalibreHardcover.validValue(CalibreHardcover.Field.ISBN13,"9780201616224")).isTrue();
        assertThat(CalibreHardcover.validValue(CalibreHardcover.Field.ISBN10,"0201616220")).isFalse();
        assertThat(CalibreHardcover.validValue(CalibreHardcover.Field.ISBN13,"9780201616220")).isFalse();
        assertThat(CalibreHardcover.validValue(CalibreHardcover.Field.ASIN,"bad")).isFalse();
    }
    @Test void missingIdentifiersAreSkippedAndTitleAuthorStillWork() {
        var result=service.search(9,null,1,true,true,true,true);
        assertThat(result.notices()).containsExactly("ISBN_UNAVAILABLE","ASIN_UNAVAILABLE");
        assertThat(result.identifiers()).isEqualTo(metadata.identifiers());
        assertThat(result.items()).hasSize(1);
        verify(hardcover).search("Book",List.of("Author"),1);
        verify(hardcover,never()).searchIdentifiers(anyList(),anyString(),anyInt());
    }
    @Test void noUsableCriteriaNeverQueriesHardcover() {
        var result=service.search(9,null,1,false,false,true,true);
        assertThat(result.notices()).contains("ISBN_UNAVAILABLE","ASIN_UNAVAILABLE","NO_CRITERIA");
        assertThat(result.items()).isEmpty();
        verify(hardcover,never()).search(anyString(),anyList(),anyInt());
        verify(hardcover,never()).searchIdentifiers(anyList(),anyString(),anyInt());
    }
    @Test void invalidIdentifiersAreNotSentOrUsedToBroadenTheQuery() {
        when(calibre.identifierMetadata(connection,9)).thenReturn(new CalibreClient.IdentifierMetadata("uuid","Book",List.of("Author"),Map.of("isbn","9780201616220","asin","bad")));
        var result=service.search(9,null,1,false,false,true,true);
        assertThat(result.notices()).contains("NO_CRITERIA");
        verify(hardcover,never()).searchIdentifiers(anyList(),anyString(),anyInt());
    }
    @Test void validIdentifiersHavePriorityAndPaginationRetainsTheOriginalCriteria() {
        when(calibre.identifierMetadata(connection,9)).thenReturn(new CalibreClient.IdentifierMetadata("uuid","Book",List.of("Author"),Map.of("isbn","978-0-201-61622-4","isbn10","020161622X","asin","b000000001")));
        when(hardcover.searchIdentifiers(List.of("9780201616224","020161622X"),"B000000001",1)).thenReturn(new HardcoverClient.Search(List.of(),true));
        when(hardcover.searchIdentifiers(List.of("9780201616224","020161622X"),"B000000001",2)).thenReturn(new HardcoverClient.Search(List.of(),false));
        var result=service.search(9,null,1,true,true,true,true);
        assertThat(result.notices()).containsExactly("EXACT_IDENTIFIERS");
        service.search(9,result.token(),2,true,true,false,false);
        verify(hardcover).searchIdentifiers(List.of("9780201616224","020161622X"),"B000000001",2);
        verify(hardcover,never()).search(anyString(),anyList(),anyInt());
    }
    @Test void customQueryDoesNotChangeTheMetadataUsedForConfirmation() {
        var match=new HardcoverClient.Match(7,"dune","Dune",List.of("Frank Herbert"));
        when(hardcover.search("Dune",List.of("Frank Herbert"),1)).thenReturn(new HardcoverClient.Search(List.of(match),true));
        when(hardcover.search("Dune",List.of("Frank Herbert"),2)).thenReturn(new HardcoverClient.Search(List.of(match),false));
        var search=service.search(9,null,1,true,true,false,false," Dune "," Frank Herbert ");
        assertThat(search.title()).isEqualTo("Book");assertThat(search.authors()).containsExactly("Author");
        service.search(9,search.token(),2,true,true,false,false,"Ignored","Ignored");
        verify(hardcover).search("Dune",List.of("Frank Herbert"),2);
        var preview=service.preview(search.token(),7,null,Map.of(CalibreHardcover.Field.BOOK_ID,"hardcover_book"));
        service.apply(preview.token());verify(calibre).addIdentifiers(connection,9,metadata,Map.of("hardcover_book","7"));
        assertThatThrownBy(()->service.search(9,null,1,true,true,false,false,"x".repeat(501),"Author")).hasMessageContaining("larga");
    }
    @Test void manualSlugMustResolveBeforeItCanBePreviewedAndStillPreservesExistingMetadata() {
        var source=service.search(9,null,1);
        when(hardcover.bookBySlug("manual-book")).thenReturn(new HardcoverClient.Match(8,"manual-book","Manual book",List.of("Author")));
        when(hardcover.book(8)).thenReturn(new HardcoverClient.Match(8,"manual-book","Manual book",List.of("Author")));
        assertThatThrownBy(()->service.preview(source.token(),8,null,Map.of(CalibreHardcover.Field.BOOK_ID,"hardcover_book"))).hasMessageContaining("resultados");
        assertThat(service.resolveSlug(source.token()," manual-book ").id()).isEqualTo(8);
        var preview=service.preview(source.token(),8,null,Map.of(CalibreHardcover.Field.SLUG,"hardcover",CalibreHardcover.Field.BOOK_ID,"hardcover_book",CalibreHardcover.Field.EDITION_ID,"hardcover_edition"));
        assertThat(preview.values()).extracting(v->v.value()).containsExactly("manual-book","8","");
        service.apply(preview.token());verify(calibre).addIdentifiers(connection,9,metadata,Map.of("hardcover","manual-book","hardcover_book","8"));
        owner("other");assertThatThrownBy(()->service.resolveSlug(source.token(),"manual-book")).hasMessageContaining("usuario");
    }
    @Test void editionLanguagesAndFilteredPagesRequireAnAuthorizedSelectedBook() {
        var source=service.search(9,null,1);
        when(hardcover.editionLanguages(7)).thenReturn(List.of(new HardcoverClient.Language("es","Spanish")));
        assertThat(service.editionLanguages(source.token(),7)).hasSize(1);
        when(hardcover.editions(7,2,"es")).thenReturn(new HardcoverClient.Editions(List.of(),false));
        service.editions(source.token(),7,2,"es");verify(hardcover).editions(7,2,"es");
        assertThatThrownBy(()->service.editionLanguages(source.token(),8)).hasMessageContaining("resultados");
        owner("other");assertThatThrownBy(()->service.editions(source.token(),7,1,"es")).hasMessageContaining("usuario");
    }
    @Test void manualIdentifiersWorkWithoutIdentifiersInCalibreAndDoNotWriteMetadata() {
        when(hardcover.searchIdentifiers(List.of("9780201616224"),"B000000001",1)).thenReturn(new HardcoverClient.Search(List.of(),true));
        when(hardcover.searchIdentifiers(List.of("9780201616224"),"B000000001",2)).thenReturn(new HardcoverClient.Search(List.of(),false));
        var result=service.search(9,null,1,false,false,true,true,"","","978-0-201-61622-4","b000000001");
        assertThat(result.queryIsbn()).isEqualTo("9780201616224");assertThat(result.queryAsin()).isEqualTo("B000000001");
        assertThat(result.notices()).containsExactly("EXACT_IDENTIFIERS");
        service.search(9,result.token(),2,false,false,true,true,"","","020161622X","B000000002");
        verify(hardcover).searchIdentifiers(List.of("9780201616224"),"B000000001",2);
        verify(calibre,never()).addIdentifiers(any(),anyLong(),any(),any());
    }
    @Test void invalidManualIdentifiersAreExplainedWithoutCallingTheApi() {
        assertThatThrownBy(()->service.search(9,null,1,false,false,true,false,"","","9780201616220","")).hasMessageContaining("ISBN introducido");
        assertThatThrownBy(()->service.search(9,null,1,false,false,false,true,"","","","bad")).hasMessageContaining("ASIN introducido");
        verify(hardcover,never()).searchIdentifiers(anyList(),anyString(),anyInt());
        verify(hardcover,never()).search(anyString(),anyList(),anyInt());
    }
    @Test void manualEditionAuthorizesItsBookWithoutChangingTheCalibreEntry() {
        var source=service.search(9,null,1);
        var book=new HardcoverClient.Match(8,"other-book","Other book",List.of("Author"));
        var edition=new HardcoverClient.Edition(22,"Other edition","es","Publisher","","","");
        when(hardcover.editionBook(22)).thenReturn(new HardcoverClient.EditionBook(book,edition));when(hardcover.book(8)).thenReturn(book);when(hardcover.edition(8,22)).thenReturn(edition);
        assertThat(service.resolveEdition(source.token(),22).book().id()).isEqualTo(8);
        var preview=service.preview(source.token(),8,22L,Map.of(CalibreHardcover.Field.SLUG,"hardcover",CalibreHardcover.Field.BOOK_ID,"hardcover_book",CalibreHardcover.Field.EDITION_ID,"hardcover_edition"));
        assertThat(preview.values()).extracting(v->v.value()).containsExactly("other-book","8","22");
        verify(calibre,never()).addIdentifiers(any(),anyLong(),any(),any());
        owner("other");assertThatThrownBy(()->service.resolveEdition(source.token(),22)).hasMessageContaining("usuario");
    }
    @Test void reusesSearchLanguagesAndEditionPagesAndDoesNotRefetchKnownSelections() {
        var source=service.search(9,null,1);
        var first=new HardcoverClient.Edition(11,"Edition","es","Publisher","020161622X","9780201616224","");
        var second=new HardcoverClient.Edition(12,"Other edition","es","Publisher","","","");
        when(hardcover.editionLanguages(7)).thenReturn(List.of(new HardcoverClient.Language("es","Spanish")));
        when(hardcover.editions(7,1,"es")).thenReturn(new HardcoverClient.Editions(List.of(first,second),false));
        service.search(9,source.token(),1);service.editionLanguages(source.token(),7);service.editionLanguages(source.token(),7);
        service.editions(source.token(),7,1,"es");service.editions(source.token(),7,1,"es");
        var fields=Map.of(CalibreHardcover.Field.BOOK_ID,"hardcover_book",CalibreHardcover.Field.EDITION_ID,"hardcover_edition");
        service.preview(source.token(),7,11L,fields);service.preview(source.token(),7,12L,fields);var preview=service.preview(source.token(),7,11L,fields);
        assertThat(service.resolveSlug(source.token(),"book-slug").id()).isEqualTo(7);
        assertThat(service.resolveEdition(source.token(),11).book().id()).isEqualTo(7);
        verify(hardcover).search("Book",List.of("Author"),1);verify(hardcover).editionLanguages(7);verify(hardcover).editions(7,1,"es");
        verify(hardcover,never()).book(anyLong());verify(hardcover,never()).edition(anyLong(),anyLong());verify(hardcover,never()).bookBySlug(anyString());verify(hardcover,never()).editionBook(anyLong());
        verify(calibre,times(4)).identifierMetadata(connection,9);
        service.apply(preview.token());verify(calibre).addIdentifiers(connection,9,metadata,Map.of("hardcover_book","7","hardcover_edition","11"));
    }
    @Test void cacheFailuresAreNotStoredAndCachedDataStillRequiresTheSameOwnerAndLibrary() {
        var source=service.search(9,null,1);
        when(hardcover.editionLanguages(7)).thenThrow(new RuntimeException("temporary")).thenReturn(List.of(new HardcoverClient.Language("es","Spanish")));
        assertThatThrownBy(()->service.editionLanguages(source.token(),7)).hasMessage("temporary");
        assertThat(service.editionLanguages(source.token(),7)).hasSize(1);verify(hardcover,times(2)).editionLanguages(7);
        owner("other");assertThatThrownBy(()->service.editionLanguages(source.token(),7)).hasMessageContaining("usuario");
        owner("owner");when(settings.connection()).thenReturn(new CalibreSettings.Connection("http://other.test","","","main"));
        assertThatThrownBy(()->service.editionLanguages(source.token(),7)).hasMessageContaining("cambió");
        when(settings.connection()).thenReturn(connection);service.discard(source.token());
        assertThatThrownBy(()->service.editionLanguages(source.token(),7)).hasMessageContaining("caducado");
    }
    @Test void concurrentRequestsForTheSamePageOnlyFetchItOnce() throws Exception {
        var source=service.search(9,null,1);var auth=SecurityContextHolder.getContext().getAuthentication();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(hardcover.editions(7,1,"es")).thenAnswer(call->{entered.countDown();if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("not released");return new HardcoverClient.Editions(List.of(),false);});
        java.util.concurrent.Callable<HardcoverClient.Editions> load=()->{SecurityContextHolder.getContext().setAuthentication(auth);try{return service.editions(source.token(),7,1,"es");}finally{SecurityContextHolder.clearContext();}};
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(load);assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var second=executor.submit(load);release.countDown();
            assertThat(first.get(3,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(3,java.util.concurrent.TimeUnit.SECONDS));
        } finally {release.countDown();}
        verify(hardcover).editions(7,1,"es");
    }
    @Test void calibreCoverIsCachedAndNeverExposedWithoutFilePermission() {
        var source=service.search(9,null,1);assertThat(source.coverUrl()).isEqualTo("/api/calibre/hardcover/cover?token="+source.token());
        when(calibre.cover(connection,9)).thenReturn(new CalibreClient.Cover(new byte[]{1,2,3},"image/jpeg"));
        assertThat(service.cover(source.token()).bytes()).containsExactly(1,2,3);service.cover(source.token());verify(calibre).cover(connection,9);
        var account=sessions.current();SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALIBRE_READ"),new org.springframework.security.core.authority.SimpleGrantedAuthority("CALIBRE_SEND"))));
        assertThat(service.search(9,null,1).coverUrl()).isNull();
        assertThatThrownBy(()->service.cover(source.token())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(calibre).cover(connection,9);
        owner("other");assertThatThrownBy(()->service.cover(source.token())).hasMessageContaining("usuario");
    }
    @Test void thumbnailResponsesArePrivateAndDoNotSniffContent() {
        var source=service.search(9,null,1);when(calibre.cover(connection,9)).thenReturn(new CalibreClient.Cover(new byte[]{1,2,3},"image/png"));
        var response=new CalibreHardcoverController(service).cover(source.token());
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        var absent=service.search(9,null,1);when(calibre.cover(connection,9)).thenReturn(new CalibreClient.Cover(new byte[0],"image/jpeg"));
        assertThat(new CalibreHardcoverController(service).cover(absent.token()).getStatusCode().value()).isEqualTo(404);
    }
    @Test void discardedSearchCannotBeApplied() {
        var s=service.search(9,null,1);var p=service.preview(s.token(),7,null,Map.of(CalibreHardcover.Field.BOOK_ID,"hc"));service.discard(s.token());assertThatThrownBy(()->service.apply(p.token())).hasMessageContaining("caducado");
    }
}
