package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CalibreIdentifiersTests {
    @TempDir Path root;
    JdbcTemplate jdbc;CalibreIdentifiers identifiers;CalibreClient client;CalibreSettings settings;CalibreTransfers transfers;EpubFiles files;SessionAccess sessions;
    CalibreSettings.Connection c=new CalibreSettings.Connection("http://calibre.test","","","main");
    byte[] epub;CalibreClient.Book book;
    static byte[] epub(String body) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            for(var entry:Map.of("mimetype","application/epub+zip","META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>","content.opf","<package xmlns='http://www.idpf.org/2007/opf'><metadata/></package>","chapter.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><body>"+body+"</body></html>").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }return output.toByteArray();
    }
    static Path withOpf(Path target,byte[] epub,String opf) throws Exception {
        try(var input=new ZipInputStream(new java.io.ByteArrayInputStream(epub));var output=new ZipOutputStream(Files.newOutputStream(target))) {
            for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(entry.getName().equals("content.opf")?opf.getBytes(StandardCharsets.UTF_8):input.readAllBytes());output.closeEntry();
            }
        }return target;
    }
    @Test void fingerprintIgnoresOnlyMetadataButDetectsReadingOrderChanges() throws Exception {
        String prefix="<package xmlns='http://www.idpf.org/2007/opf'>";
        String spine="<manifest><item id='one' href='chapter.xhtml'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>";
        var first=withOpf(root.resolve("first.epub"),epub,prefix+"<metadata/>"+spine);
        var metadata=withOpf(root.resolve("metadata.epub"),epub,prefix+"<metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Updated title</dc:title></metadata>"+spine);
        var changed=withOpf(root.resolve("changed.epub"),epub,prefix+"<metadata/>"+spine.replace("<itemref idref='one'/><itemref idref='two'/>","<itemref idref='two'/><itemref idref='one'/>"));
        assertThat(CalibreEpubMetadata.contentFingerprint(first)).isEqualTo(CalibreEpubMetadata.contentFingerprint(metadata));
        assertThat(CalibreEpubMetadata.contentFingerprint(first)).isNotEqualTo(CalibreEpubMetadata.contentFingerprint(changed));
    }
    @BeforeEach void setup() throws Exception {
        var data=new com.rlibanez.eplsync.ordering.TextCollationDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("test.db")));
        org.flywaydb.core.Flyway.configure().dataSource(data).load().migrate();jdbc=new JdbcTemplate(data);
        client=mock(CalibreClient.class);settings=mock(CalibreSettings.class);transfers=mock(CalibreTransfers.class);files=mock(EpubFiles.class);sessions=mock(SessionAccess.class);
        var catalog=mock(CalibreCatalogService.class);
        identifiers=new CalibreIdentifiers(jdbc,client,settings,transfers,catalog,files,sessions);
        when(client.withCancellation(any(),any())).thenAnswer(call->call.<java.util.function.Supplier<Object>>getArgument(1).get());
        when(transfers.exclusive(any())).thenAnswer(call->call.<java.util.function.Supplier<Object>>getArgument(0).get());
        when(settings.connection()).thenReturn(c);
        when(files.clientInstanceId()).thenReturn("instance");
        var account=new Account("owner","owner","","ADMIN","ACTIVE",false,null,0,Permission.defaults("ADMIN"),java.time.Instant.now());
        when(sessions.current()).thenReturn(account);
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(account,null,account.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));
        epub=epub("Same text");
        when(files.download("record",0)).thenAnswer(call->new EpubFiles.Opened("book.epub",epub.length,new java.io.ByteArrayInputStream(epub)));
        doAnswer(call->{Files.write(call.getArgument(2),epub);return null;}).when(client).downloadOriginalEpub(any(),eq(9L),any(),anyLong());
        book=new CalibreClient.Book(9,"Book",List.of("Author"),null,null,false,"uuid");when(client.book(any(),eq(9L))).thenAnswer(call->book);
        when(client.completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("uuid"))).thenAnswer(call->{book=new CalibreClient.Book(9,"Book",List.of("Author"),1L,new BigDecimal("1.1"),true,"uuid");return book;});
        jdbc.update("INSERT INTO calibre_jobs(id,created_at,updated_at,owner_username,destination,library,source,state) VALUES('job','now','now','owner',?,'main','source','COMPLETED')",CalibreTransfers.destination(c));
        jdbc.update("INSERT INTO calibre_job_items(id,job_id,position,record_id,file_index,name,size,epl_id,revision,state,calibre_id,epub_identity) VALUES('item','job',0,'record',0,'book.epub',?,1,'1.1','ACCEPTED',9,'{\"eplId\":null,\"revision\":null}')",epub.length);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void previewAndApplyUseTheExactUploadAndKeepEpubIdentityUntouched() {
        var preview=identifiers.preview(9);assertThat(preview.allowed()).isTrue();assertThat(preview.eplId()).isEqualTo(1);
        var result=identifiers.apply(preview.token());assertThat(result.allowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT identifiers_completed FROM calibre_job_items",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT epub_identity FROM calibre_job_items",String.class)).isEqualTo("{\"eplId\":null,\"revision\":null}");
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    private void externalDownload(String id,long epl,String revision,byte[] content) {
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,created_at,client_instance_id,hash,client,origin,status) VALUES(?,?,?,'now','instance',?,'qbittorrent','EPLSYNC','DOWNLOADED')",id,epl,new BigDecimal(revision),id);
        when(files.completed(id)).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",content.length)));
        when(files.download(id,0)).thenAnswer(call->new EpubFiles.Opened("book.epub",content.length,new java.io.ByteArrayInputStream(content)));
    }
    @Test void manualImportMatchesDownloadAndReportsItsOriginWithoutCreatingAnUpload() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("external",1,"1.1",epub);
        var preview=identifiers.preview(9);
        assertThat(preview.allowed()).isTrue();assertThat(preview.message()).contains("Importación ajena a EPL Sync");
        assertThat(preview.eplId()).isEqualTo(1L);assertThat(preview.revision()).isEqualTo("1.1");
        var result=identifiers.apply(preview.token());assertThat(result.message()).contains("Importación ajena a EPL Sync");
        identifiers.apply(preview.token()); // A lost response can be retried against the same entry.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isZero();
        verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
    }
    @Test void identicalContentWithDifferentDownloadIdentitiesIsAmbiguous() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);externalDownload("two",2,"1.1",epub);
        assertThatThrownBy(()->identifiers.preview(9)).hasMessageContaining("distintas identidades");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void multipleCopiesOfTheSameDownloadIdentityAreSafe() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);externalDownload("two",1,"1.1",epub);
        assertThat(identifiers.preview(9).allowed()).isTrue();
    }
    @Test void manualImportWithDifferentContentIsExcluded() throws Exception {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub("Other content"));
        var preview=identifiers.preview(9);assertThat(preview.allowed()).isFalse();assertThat(preview.message()).isEqualTo("Importación ajena a EPL Sync: no hay correspondencia inequívoca");
    }
    @Test void manualImportAllowsMetadataChangesButNotConflictingIdentifiers() throws Exception {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);
        byte[] changed=Files.readAllBytes(withOpf(root.resolve("edited.epub"),epub,"<package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Edited manually</dc:title></metadata></package>"));
        doAnswer(call->{Files.write(call.getArgument(2),changed);return null;}).when(client).downloadOriginalEpub(any(),eq(9L),any(),anyLong());
        var preview=identifiers.preview(9);assertThat(preview.allowed()).isTrue();
        book=new CalibreClient.Book(9,"Book",List.of("Author"),2L,null,false,"uuid");
        assertThatThrownBy(()->identifiers.apply(preview.token())).hasMessageContaining("contradicen");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void newAmbiguousDownloadAfterPreviewPreventsWriting() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);var preview=identifiers.preview(9);
        externalDownload("two",2,"1.1",epub);
        assertThatThrownBy(()->identifiers.apply(preview.token())).hasMessageContaining("distintas identidades");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void deletedOrChangedDownloadAfterPreviewPreventsWriting() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);var preview=identifiers.preview(9);
        jdbc.update("DELETE FROM torrent_downloads");
        assertThatThrownBy(()->identifiers.apply(preview.token())).hasMessageContaining("cambiaron");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void liveCompletionWorksEvenWhenTheStoredStatusIsStale() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);jdbc.update("UPDATE torrent_downloads SET status='SUBMITTED'");
        assertThat(identifiers.preview(9).allowed()).isTrue();
        when(files.completed("one")).thenReturn(List.of());
        assertThat(identifiers.preview(9).message()).contains("no hay EPUB completos", "1 registros");
        when(files.completed("one")).thenThrow(new com.rlibanez.eplsync.exception.UserInputException("EPUB inaccesible"));
        assertThatThrownBy(()->identifiers.preview(9)).hasMessageContaining("inaccesible");
    }
    @Test void otherTorrentInstancesAreNotCandidates() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);externalDownload("old",2,"1.1",epub);
        jdbc.update("UPDATE torrent_downloads SET client_instance_id='previous-client' WHERE id='old'");
        assertThat(identifiers.preview(9).allowed()).isTrue();verify(files,never()).completed("old");
    }
    @Test void changedDownloadContentAndUuidAfterPreviewPreventWriting() throws Exception {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);var preview=identifiers.preview(9);
        byte[] other=epub("Changed publication");when(files.download("one",0)).thenAnswer(call->new EpubFiles.Opened("book.epub",other.length,new java.io.ByteArrayInputStream(other)));
        assertThatThrownBy(()->identifiers.apply(preview.token())).isInstanceOf(com.rlibanez.eplsync.exception.UserInputException.class);
        book=new CalibreClient.Book(9,"Book",List.of("Author"),null,null,false,"replaced-uuid");
        assertThatThrownBy(()->identifiers.apply(preview.token())).hasMessageContaining("entrada de Calibre cambió");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void matchingTorrentWithMultipleEpubsRequiresReview() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);
        when(files.completed("one")).thenReturn(List.of(new EpubFiles.Item(0,"book.epub",epub.length),new EpubFiles.Item(1,"another.epub",epub.length)));
        assertThatThrownBy(()->identifiers.preview(9)).hasMessageContaining("varios EPUB");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void cancellationBeforeTheRequestStartsPreventsAnyCalibreAccess() {
        String operation=UUID.randomUUID().toString();identifiers.cancel(operation);
        assertThatThrownBy(()->identifiers.preview(9,operation)).hasMessageContaining("cancelada");
        verify(client,never()).book(any(),anyLong());
    }
    @Test void cancellationWhileScanningStopsWithoutCompletingIdentifiers() {
        jdbc.update("DELETE FROM calibre_job_items");externalDownload("one",1,"1.1",epub);
        String operation=UUID.randomUUID().toString();
        when(files.completed("one")).thenAnswer(call->{identifiers.cancel(operation);return List.of(new EpubFiles.Item(0,"book.epub",epub.length));});
        assertThatThrownBy(()->identifiers.preview(9,operation)).hasMessageContaining("cancelada");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
        verify(files,never()).download("one",0);
    }
    @Test void cancellationBeforeApplyDoesNotWriteAndThePreviewCanBeRetried() {
        var preview=identifiers.preview(9);String operation=UUID.randomUUID().toString();identifiers.cancel(operation);
        assertThatThrownBy(()->identifiers.apply(preview.token(),operation)).hasMessageContaining("cancelada");
        verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
        identifiers.apply(preview.token(),UUID.randomUUID().toString());
        verify(client).completeIdentifiers(any(),eq(9L),eq(1L),any(),eq("uuid"));
    }
    @Test void cancellationCannotTargetAnotherUsersOperation() {
        String operation=UUID.randomUUID().toString();identifiers.preview(9,operation);
        var other=new Account("other","other","","ADMIN","ACTIVE",false,null,0,Permission.defaults("ADMIN"),java.time.Instant.now());when(sessions.current()).thenReturn(other);
        assertThatThrownBy(()->identifiers.cancel(operation)).hasMessageContaining("otro usuario");
    }
    @Test void noDownloadCandidatesHaveADistinctDiagnostic() {
        jdbc.update("DELETE FROM calibre_job_items");
        assertThat(identifiers.preview(9).message()).contains("no hay registros de Descargas", "sincroniza Descargas");
    }
    @Test void noTraceableUploadIsExcluded() {jdbc.update("DELETE FROM calibre_job_items");assertThat(identifiers.preview(9).allowed()).isFalse();verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());}
    @Test void differentContentIsNeverRelinked() throws Exception {
        byte[] other=epub("Different text");doAnswer(call->{Files.write(call.getArgument(2),other);return null;}).when(client).downloadOriginalEpub(any(),anyLong(),any(),anyLong());
        assertThatThrownBy(()->identifiers.preview(9)).hasMessageContaining("no coincide");verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void changedEntryAfterPreviewCannotBeEdited() {
        var preview=identifiers.preview(9);book=new CalibreClient.Book(9,"Other book",List.of("Author"),null,null,false,"new-uuid");
        assertThatThrownBy(()->identifiers.apply(preview.token())).hasMessageContaining("cambió");verify(client,never()).completeIdentifiers(any(),anyLong(),anyLong(),any(),any());
    }
    @Test void changedDestinationAndPermissionsAreRejected() {
        var preview=identifiers.preview(9);when(settings.connection()).thenReturn(new CalibreSettings.Connection("http://other.test","","","main"));
        assertThatThrownBy(()->identifiers.apply(preview.token())).hasMessageContaining("conexión");
        SecurityContextHolder.clearContext();assertThatThrownBy(()->identifiers.apply(preview.token())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
