package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentClient;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EpubFilesTests {
    @TempDir Path directory;
    DownloadRepository records=mock(DownloadRepository.class);
    DownloadTrackingService tracking=mock(DownloadTrackingService.class);
    TorrentClientService client=mock(TorrentClientService.class);
    TorrentClient adapter=mock(TorrentClient.class);
    TorrentProperties properties=new TorrentProperties();
    EpubFiles files;
    @BeforeEach void setup() {
        properties.getFiles().setRemoteRoot("/downloads");properties.getFiles().setLocalRoot(directory.toString());
        var row=new DownloadRecord();row.setClientInstanceId("server");row.setHash("A".repeat(40));
        when(records.findById("record")).thenReturn(Optional.of(row));when(tracking.instanceId()).thenReturn("server");
        when(client.exclusiveClient(any())).thenAnswer(call -> {
            Function<TorrentClient,Object> action=call.getArgument(0);return action.apply(adapter);
        });
        files=new EpubFiles(records,tracking,client,properties);
    }
    void remote(String name,long size,double progress,String save) {
        when(adapter.listFiles(anyString())).thenReturn(List.of(new TorrentClient.TorrentFile(7,name,size,progress,save)));
    }
    @Test void resolvesNestedFoldersAndStreamsExactFile() throws Exception {
        var target=directory.resolve("books/spanish/sanderson/book.epub");Files.createDirectories(target.getParent());Files.writeString(target,"epub bytes");
        remote("book.epub",10,1,"/downloads/books/spanish/sanderson");
        assertThat(files.list("record")).containsExactly(new EpubFiles.Item(7,"book.epub",10));
        try(var result=files.download("record",7)) { assertThat(result.stream().readAllBytes()).isEqualTo("epub bytes".getBytes()); }
    }
    @Test void rejectsIncompleteMissingAndChangedSize() throws Exception {
        remote("book.epub",10,.5,"/downloads");assertThatThrownBy(() -> files.list("record")).hasMessageContaining("completamente");
        remote("book.epub",10,1,"/downloads");assertThatThrownBy(() -> files.download("record",7)).hasMessageContaining("accesible");
        Files.writeString(directory.resolve("book.epub"),"short");assertThatThrownBy(() -> files.download("record",7)).hasMessageContaining("accesible");
    }
    @Test void rejectsTraversalSiblingRootAndAbsoluteNames() {
        for(var name:List.of("../secret.epub","folder/../../secret.epub","/secret.epub","C:/secret.epub"))
            assertThatThrownBy(() -> EpubFiles.relative("/downloads","/downloads",name)).isInstanceOf(TorrentOperationException.class);
        assertThatThrownBy(() -> EpubFiles.relative("/downloads","/downloads-other","book.epub")).isInstanceOf(TorrentOperationException.class);
        assertThatThrownBy(() -> EpubFiles.relative("/downloads","/downloads/../private","book.epub")).isInstanceOf(TorrentOperationException.class);
        assertThat(EpubFiles.relative("/","/books","book.epub")).isEqualTo(Path.of("books/book.epub"));
        assertThat(EpubFiles.relative("C:/","C:/books","book.epub")).isEqualTo(Path.of("books/book.epub"));
        assertThat(EpubFiles.relative("C:\\Downloads","C:\\Downloads\\books","book.epub")).isEqualTo(Path.of("books/book.epub"));
    }
    @Test void rejectsSymlinkFilesAndDirectories() throws Exception {
        var external=Files.createTempDirectory(directory.getParent(),"outside-epub-");
        try {
            Files.writeString(external.resolve("book.epub"),"bytes");
            Files.createSymbolicLink(directory.resolve("book.epub"),external.resolve("book.epub"));
            remote("book.epub",5,1,"/downloads");assertThatThrownBy(() -> files.download("record",7)).hasMessageContaining("accesible");
            Files.createSymbolicLink(directory.resolve("linked"),external);
            remote("linked/book.epub",5,1,"/downloads");assertThatThrownBy(() -> files.download("record",7)).hasMessageContaining("accesible");
        } finally { Files.deleteIfExists(external.resolve("book.epub"));Files.delete(external); }
    }
    @Test void rejectsOldDestinationAndDisabledFolderAccess() {
        when(tracking.instanceId()).thenReturn("new-server");
        assertThatThrownBy(() -> files.list("record")).hasMessageContaining("otro cliente");verify(adapter,never()).listFiles(anyString());
        properties.getFiles().setLocalRoot("");assertThatThrownBy(() -> files.list("record")).hasMessageContaining("Configura");
    }
    @Test void localDockerDefaultDoesNotEnableAccessWithoutRemoteRoot() {
        properties.getFiles().setRemoteRoot("");
        properties.getFiles().setLocalRoot("/app/downloads");
        assertThatThrownBy(() -> files.list("record")).hasMessageContaining("Configura");
        assertThatThrownBy(() -> files.download("record",7)).hasMessageContaining("Configura");
        verifyNoInteractions(client,records,adapter);
    }
    @Test void offersAllCompletedEpubsAndUsesFileIndex() throws Exception {
        Files.writeString(directory.resolve("one.epub"),"one");Files.writeString(directory.resolve("two.epub"),"two");
        when(adapter.listFiles(anyString())).thenReturn(List.of(new TorrentClient.TorrentFile(4,"one.epub",3,1,"/downloads"),new TorrentClient.TorrentFile(8,"two.epub",3,1,"/downloads"),new TorrentClient.TorrentFile(9,"cover.jpg",100,1,"/downloads")));
        assertThat(files.list("record")).hasSize(2);
        try(var result=files.download("record",8)) { assertThat(result.name()).isEqualTo("two.epub"); }
        assertThatThrownBy(() -> files.download("record",9)).hasMessageContaining("no existe");
    }
    @Test void preparesSharedFilesOnceAndRejectsChangedConfiguration() throws Exception {
        Files.writeString(directory.resolve("book.epub"),"bytes");remote("book.epub",5,1,"/downloads");
        var existing=records.findById("record");when(records.findById("other")).thenReturn(existing);
        var plan=files.prepare(List.of("record","other"));
        assertThat(plan.archive()).isTrue();assertThat(plan.items()).hasSize(1);
        verify(adapter,times(1)).listFiles(anyString());files.validate(plan);
        properties.getFiles().setRemoteRoot("/new");
        assertThatThrownBy(() -> files.validate(plan)).hasMessageContaining("ha cambiado");
    }
    @Test void preparesSingleEpubWithoutZipAndRejectsMissingFilesBeforeDownload() throws Exception {
        Files.writeString(directory.resolve("book.epub"),"bytes");remote("book.epub",5,1,"/downloads");
        var plan=files.prepare(List.of("record"));assertThat(plan.archive()).isFalse();
        Files.delete(directory.resolve("book.epub"));
        assertThatThrownBy(() -> files.validate(plan)).hasMessageContaining("No se ha iniciado");
        assertThatThrownBy(() -> files.prepare(List.of("record"))).hasMessageContaining("registros afectados");
    }
    @Test void rejectsInvalidSelectionBeforeContactingClient() {
        assertThatThrownBy(() -> files.prepare(Collections.nCopies(1001,"record"))).hasMessageContaining("1000");
        assertThatThrownBy(() -> files.prepare(List.of())).hasMessageContaining("1000");
        verifyNoInteractions(client);
    }
}
