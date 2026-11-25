package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalibreRevisionConfirmationTests {
    @TempDir Path root;
    JdbcTemplate jdbc;CalibreClient client;CalibreSettings settings;CalibreTransfers transfers;EpubFiles files;
    CalibreRevisionConfirmation service;org.springframework.jdbc.datasource.DataSourceTransactionManager manager;
    CalibreSettings.Connection c=new CalibreSettings.Connection("http://calibre.test","","","main");
    byte[] original,remote;CalibreClient.Book book;CalibreClient.IdentifierMetadata metadata;int writes;
    byte[] epub(long epl,String revision,String text) throws Exception {
        return Files.readAllBytes(CalibreIdentifiersTests.withOpf(root.resolve(UUID.randomUUID()+".epub"),CalibreIdentifiersTests.epub(text),
            "<package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier>epl:"+epl+"</dc:identifier><dc:identifier>revision:"+revision+"</dc:identifier></metadata></package>"));
    }
    void revision(String value) {
        var ids=new HashMap<>(Map.of("epl","48628","revision",value,"isbn","123","hardcover_book","321"));
        metadata=new CalibreClient.IdentifierMetadata("uuid","Book",List.of("Author"),ids);
        book=new CalibreClient.Book(2999,"Book",List.of("Author"),48628L,new BigDecimal(value),true,"uuid",List.of("EPUB"),ids);
    }
    @BeforeEach void setup() throws Exception {
        var data=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("test.db")));
        org.flywaydb.core.Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(data);
        client=mock(CalibreClient.class);settings=mock(CalibreSettings.class);transfers=mock(CalibreTransfers.class);files=mock(EpubFiles.class);
        service=new CalibreRevisionConfirmation(jdbc,manager,transfers,settings,client,mock(CalibreCatalogService.class),files);
        when(transfers.exclusive(any())).thenAnswer(call->call.<java.util.function.Supplier<Object>>getArgument(0).get());
        when(transfers.source()).thenReturn("source");when(settings.connection()).thenReturn(c);
        var account=new Account("owner","Owner","","ADMIN","ACTIVE",false,null,0,Permission.defaults("ADMIN"),Instant.now());
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account,null,account.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));
        original=epub(48628,"1.0","Same text");remote=original;revision("1.0");
        when(files.download("record",0)).thenAnswer(call->new EpubFiles.Opened("book.epub",original.length,new java.io.ByteArrayInputStream(original)));
        when(client.book(any(),eq(2999L))).thenAnswer(call->book);
        when(client.identifierMetadata(any(),eq(2999L))).thenAnswer(call->metadata);
        doAnswer(call->{Files.write(call.getArgument(2),remote);return null;}).when(client).downloadOriginalEpub(any(),eq(2999L),any(),anyLong());
        when(client.hasEpub(any(),eq(2999L))).thenReturn(true);
        when(client.updateIdentifiers(any(),eq(2999L),any(),any(),any())).thenAnswer(call->{
            assertThat(call.<Map<String,String>>getArgument(3)).containsExactlyEntriesOf(Map.of("revision","1.2"));
            assertThat(call.<Set<String>>getArgument(4)).containsExactly("revision");
            if(!metadata.identifiers().get("revision").equals("1.2")) {writes++;revision("1.2");}return metadata;
        });
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,created_at,client_instance_id,hash,client,origin,status) VALUES('record',48628,1.2,'now','instance','hash','qbittorrent','EPLSYNC','DOWNLOADED')");
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('job','now','now','Owner',?,'main','source','PAUSED')",CalibreTransfers.destination(c));
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,calibre_id,calibre_uuid,cleanup_policy,cleanup_ids,epub_identity) VALUES('item','job',0,'record',0,'book.epub',?,48628,'1.2','CLEANUP_PENDING',2999,'uuid','BOOK','[1]','{\"eplId\":48628,\"revision\":1.0}')",original.length);
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    @Test void authorizesNewImportWithoutComparingOrChangingTheOldPublication() throws Exception {
        remote=epub(48628,"1.0","Modified old book");
        var p=service.preview("job","item");assertThat(p.epubRevision()).isEqualTo("1");
        service.apply("job","item",new CalibreRevisionConfirmation.Input(p.token(),true));
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_jobs",String.class)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT state FROM calibre_job_items",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT calibre_id FROM calibre_job_items",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT cleanup_ids FROM calibre_job_items",String.class)).contains("2999");
        assertThat(jdbc.queryForMap("SELECT mode,old_calibre_id,applied_at FROM calibre_revision_confirmations")).containsEntry("mode","IMPORT").containsEntry("old_calibre_id",2999).containsEntry("applied_at",null);
        verify(client,never()).downloadOriginalEpub(any(),anyLong(),any(),anyLong());
        verify(client,never()).updateIdentifiers(any(),anyLong(),any(),any(),any());
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        assertThatThrownBy(()->service.apply("job","item",new CalibreRevisionConfirmation.Input(p.token(),true))).hasMessageContaining("pausado");
    }
    void authorize() {var p=service.preview("job","item");service.apply("job","item",new CalibreRevisionConfirmation.Input(p.token(),true));}
    CalibreClient.Book newBook(String revision) {return new CalibreClient.Book(4000,"Book",List.of("Author"),48628L,new BigDecimal(revision),true,"new-uuid",List.of("EPUB"),Map.of("epl","48628","revision",revision,"isbn","new-isbn"));}
    CalibreTransfers.Item importedItem() {return new CalibreTransfers.Item("item","record",0,"book.epub",original.length,48628,"1.2","UNCERTAIN",4000L,null,true,null);}
    @Test void comparesOnlyTheNewImportAndPatchesOnlyItsRevision() {
        authorize();var raw=newBook("1.0");
        var before=new CalibreClient.IdentifierMetadata("new-uuid","Book",List.of("Author"),raw.identifiers());
        when(client.identifierMetadata(c,4000)).thenReturn(before);
        when(client.book(c,4000)).thenReturn(newBook("1.2"));when(client.hasEpub(c,4000)).thenReturn(true);
        doAnswer(call->{Files.write(call.getArgument(2),original);return null;}).when(client).downloadOriginalEpub(any(),eq(4000L),any(),anyLong());
        assertThat(service.verifyImported(c,raw,importedItem()).revision()).isEqualByComparingTo("1.2");
        verify(client).updateIdentifiers(c,4000,before,Map.of("revision","1.2"),Set.of("revision"));
        verify(client,never()).updateIdentifiers(any(),eq(2999L),any(),any(),any());
        assertThat(jdbc.queryForObject("SELECT applied_at FROM calibre_revision_confirmations",String.class)).isNotBlank();
    }
    @Test void lostMetadataResponseReusesThePinnedNewEntryAfterRestart() {
        authorize();var raw=newBook("1.0");var before=new CalibreClient.IdentifierMetadata("new-uuid","Book",List.of("Author"),raw.identifiers());
        when(client.identifierMetadata(c,4000)).thenReturn(before);doAnswer(call->{Files.write(call.getArgument(2),original);return null;}).when(client).downloadOriginalEpub(any(),eq(4000L),any(),anyLong());
        when(client.updateIdentifiers(any(),eq(4000L),any(),any(),any())).thenThrow(new IllegalStateException("lost response"));
        assertThatThrownBy(()->service.verifyImported(c,raw,importedItem())).hasMessageContaining("lost response");
        assertThat(jdbc.queryForObject("SELECT new_calibre_id FROM calibre_revision_confirmations",Long.class)).isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT applied_at FROM calibre_revision_confirmations",String.class)).isNull();
        service=new CalibreRevisionConfirmation(jdbc,manager,transfers,settings,client,mock(CalibreCatalogService.class),files);
        when(client.updateIdentifiers(any(),eq(4000L),any(),any(),any())).thenReturn(before);
        when(client.identifierMetadata(c,4000)).thenReturn(new CalibreClient.IdentifierMetadata("new-uuid","Book",List.of("Author"),newBook("1.2").identifiers()));
        when(client.book(c,4000)).thenReturn(newBook("1.2"));when(client.hasEpub(c,4000)).thenReturn(true);
        service.verifyImported(c,newBook("1.2"),importedItem());
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        assertThat(jdbc.queryForObject("SELECT applied_at FROM calibre_revision_confirmations",String.class)).isNotBlank();
    }
    @Test void refusesOldEntryAndDifferentNewPublicationWithoutDeletingAnything() throws Exception {
        authorize();assertThatThrownBy(()->service.verifyImported(c,book,importedItem())).hasMessageContaining("antigua");
        var raw=newBook("1.0");when(client.identifierMetadata(c,4000)).thenReturn(new CalibreClient.IdentifierMetadata("new-uuid","Book",List.of("Author"),raw.identifiers()));
        byte[] other=epub(48628,"1.0","Incorrect newly imported content");
        doAnswer(call->{Files.write(call.getArgument(2),other);return null;}).when(client).downloadOriginalEpub(any(),eq(4000L),any(),anyLong());
        assertThatThrownBy(()->service.verifyImported(c,raw,importedItem())).hasMessageContaining("no coincide");
        verify(client,never()).updateIdentifiers(any(),anyLong(),any(),any(),any());verify(client,never()).remove(any(),anyLong(),anyBoolean());
    }
    @Test void rejectsChangedSourceAndEntryAndRequiresConsentAndPermissions() throws Exception {
        var p=service.preview("job","item");
        assertThatThrownBy(()->service.apply("job","item",new CalibreRevisionConfirmation.Input(p.token(),false))).hasMessageContaining("explícitamente");
        metadata=new CalibreClient.IdentifierMetadata("replacement","Book",List.of("Author"),metadata.identifiers());
        assertThatThrownBy(()->service.apply("job","item",new CalibreRevisionConfirmation.Input(p.token(),true))).hasMessageContaining("identidad");
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reader",null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("CALIBRE_READ"))));
        assertThatThrownBy(()->service.preview("job","item")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
