package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.torrent.TorrentClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","eplsync.torrent.inventory.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false"})
class DownloadFileInventoryTests {
    @Autowired DownloadRepository downloads;
    @Autowired DownloadFileInventory inventory;
    @Autowired DownloadQueryService queries;
    @Autowired DownloadTrackingService tracking;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String record;
    @BeforeEach void setup() {
        downloads.deleteAll();record=create("A");
    }
    String create(String hash) {
        var row=new DownloadRecord();row.setEplId(123L);row.setRevision(1.1);row.setHash(hash.repeat(40));row.setClient("qbittorrent");row.setClientInstanceId(tracking.instanceId());row.setStatus(DownloadStatus.DOWNLOADING);row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());return downloads.save(row).getId();
    }
    @Test void inventoryKeepsNestedPathsAndFiltersLocallyIncludingNegationAndUnknown() {
        String unknown=create("B");
        inventory.record(record,tracking.instanceId(),"A".repeat(40),List.of(new TorrentClient.TorrentFile(0,"folder/book.EPUB",100,.5,"/private/downloads"),new TorrentClient.TorrentFile(1,"folder/book.pdf",200,0,"/private/downloads")));
        var filters=new LinkedMultiValueMap<String,String>();filters.set("extensions","epub,pdf");
        var result=queries.search(filters);assertThat(result.items()).singleElement().satisfies(row->{assertThat(row.getFileExtensions()).containsExactly("epub","pdf");assertThat(row.getStatus()).isEqualTo(DownloadStatus.DOWNLOADING);});
        assertThat(jdbc.queryForObject("SELECT relative_path FROM torrent_download_files WHERE file_index=0",String.class)).isEqualTo("folder/book.EPUB");
        filters.set("extensionsOperator","ne");assertThat(queries.search(filters).items()).singleElement().satisfies(row->assertThat(row.getId()).isEqualTo(unknown));
        filters.set("extensions","mobi");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(record,unknown);
        filters.clear();filters.set("inventoryState","UNKNOWN");assertThat(queries.search(filters).items()).singleElement().satisfies(row->assertThat(row.getId()).isEqualTo(unknown));
    }
    @Test void extensionsSortAndUnknownFilterUseTheEntireInventory() {
        String pdf=create("B"),unknown=create("C");
        inventory.record(record,tracking.instanceId(),"A".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.pdf",10,1,"/downloads"),new TorrentClient.TorrentFile(1,"book.epub",10,1,"/downloads")));
        inventory.record(pdf,tracking.instanceId(),"B".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.pdf",10,1,"/downloads")));
        var filters=new LinkedMultiValueMap<String,String>();filters.set("sort","fileExtensions,asc");
        assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactly(unknown,record,pdf);
        filters.set("extensions","UNKNOWN,pdf");assertThat(queries.search(filters).items()).hasSize(3);
        filters.set("extensions","UNKNOWN");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactly(unknown);
        filters.set("extensionsOperator","ne");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactly(record,pdf);
    }
    @Test void unifiedExtensionFilterDistinguishesPendingErrorsAndOtherFormats() {
        String mixed=create("B"),other=create("C"),pending=create("D"),failed=create("E"),cached=create("F"),noExtension=create("0");
        inventory.record(record,tracking.instanceId(),"A".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.pdf",10,1,"/downloads"),new TorrentClient.TorrentFile(1,"comic.cbr",10,1,"/downloads")));
        inventory.record(mixed,tracking.instanceId(),"B".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.pdf",10,1,"/downloads"),new TorrentClient.TorrentFile(1,"notes.txt",10,1,"/downloads")));
        inventory.record(other,tracking.instanceId(),"C".repeat(40),List.of(new TorrentClient.TorrentFile(0,"comic.cbz",10,1,"/downloads")));
        inventory.record(cached,tracking.instanceId(),"F".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.epub",10,1,"/downloads")));
        inventory.record(noExtension,tracking.instanceId(),"0".repeat(40),List.of(new TorrentClient.TorrentFile(0,"README",10,1,"/downloads")));
        jdbc.update("UPDATE torrent_downloads SET inventory_state='ERROR' WHERE id IN (?,?)",failed,cached);
        var filters=new LinkedMultiValueMap<String,String>();filters.set("extensions","OTHER");
        assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(mixed,other,noExtension);
        filters.set("extensionsOperator","ne");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(record,cached,pending,failed);
        filters.remove("extensionsOperator");filters.set("extensions","UNKNOWN");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactly(pending);
        filters.set("extensions","ERROR");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(failed,cached);
        filters.set("extensions","ERROR,pdf");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(record,mixed,failed,cached);
        filters.set("extensions","ERROR,UNKNOWN");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(failed,cached,pending);
        filters.set("extensionsOperator","ne");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(record,mixed,other,noExtension);
        filters.set("extensions","txt");filters.remove("extensionsOperator");assertThat(queries.search(filters).items()).extracting(DownloadRecord::getId).containsExactly(mixed);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"epub","pdf","OTHER","ERROR","UNKNOWN","epub,ERROR","epub,pdf"})
    void negationIsTheExactComplementIncludingUnknownAndFailedChecks(String selection) {
        String pdf=create("B"),pending=create("C"),failed=create("D"),cached=create("E"),noExtension=create("F"),other=create("0");
        inventory.record(record,tracking.instanceId(),"A".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.epub",10,1,"/downloads")));
        inventory.record(pdf,tracking.instanceId(),"B".repeat(40),List.of(new TorrentClient.TorrentFile(0,"book.pdf",10,1,"/downloads")));
        inventory.record(cached,tracking.instanceId(),"E".repeat(40),List.of(new TorrentClient.TorrentFile(0,"cached.epub",10,1,"/downloads")));
        inventory.record(noExtension,tracking.instanceId(),"F".repeat(40),List.of(new TorrentClient.TorrentFile(0,"README",10,1,"/downloads")));
        inventory.record(other,tracking.instanceId(),"0".repeat(40),List.of(new TorrentClient.TorrentFile(0,"notes.txt",10,1,"/downloads")));
        jdbc.update("UPDATE torrent_downloads SET inventory_state='ERROR' WHERE id IN (?,?)",failed,cached);
        String excluded=create("1");jdbc.update("UPDATE torrent_downloads SET status='NOT_FOUND' WHERE id=?",excluded);
        var filters=new LinkedMultiValueMap<String,String>();filters.set("status","DOWNLOADING");filters.set("extensions",selection);
        var matching=queries.search(filters);long positive=queries.summary(filters).total();
        filters.set("extensionsOperator","ne");var opposite=queries.search(filters);long negative=queries.summary(filters).total();
        assertThat(positive+negative).isEqualTo(7);
        assertThat(matching.meta().totalItems()).isEqualTo(positive);assertThat(opposite.meta().totalItems()).isEqualTo(negative);
        var ids=new HashSet<String>();matching.items().forEach(row->ids.add(row.getId()));
        for(var row:opposite.items()) assertThat(ids.add(row.getId())).isTrue();
        assertThat(ids).containsExactlyInAnyOrder(record,pdf,pending,failed,cached,noExtension,other);
        if(selection.equals("epub")) assertThat(opposite.items()).extracting(DownloadRecord::getId).containsExactlyInAnyOrder(pdf,pending,failed,noExtension,other);
    }
    @Test void anUnavailableClientDefersTheWholeInventoryAndRecoveryResumesWithoutDroppingCachedFiles() {
        downloads.deleteAll();String instance=tracking.instanceId();
        for(String id:List.of("a","b","c")) jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,123,1,?,'qbittorrent',?,'DOWNLOADING','EPLSYNC',0)",id,id.toUpperCase(Locale.ROOT).repeat(40),instance);
        inventory.record("c",instance,"C".repeat(40),List.of(new TorrentClient.TorrentFile(0,"cached.epub",10,1,"/downloads")));
        jdbc.update("UPDATE torrent_downloads SET inventory_state='ERROR' WHERE id='c'");
        var service=org.mockito.Mockito.mock(com.rlibanez.eplsync.service.TorrentClientService.class);
        var gate=org.mockito.Mockito.mock(com.rlibanez.eplsync.maintenance.MaintenanceGate.class);org.mockito.Mockito.when(gate.enter(false)).thenReturn(true);
        var sql=org.mockito.Mockito.spy(jdbc);var clock=new java.util.concurrent.atomic.AtomicLong();
        var properties=new com.rlibanez.eplsync.config.TorrentProperties();
        var worker=new DownloadFileInventory(sql,transactions,service,tracking,gate,properties,clock::get);
        try {
            org.mockito.Mockito.when(service.synchronizeClient(org.mockito.ArgumentMatchers.any())).thenThrow(new com.rlibanez.eplsync.qbittorrent.QBittorrentConnectionException(com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.UPSTREAM));
            worker.tick();org.mockito.Mockito.verify(service,org.mockito.Mockito.times(1)).synchronizeClient(org.mockito.ArgumentMatchers.any());
            assertThat(jdbc.queryForObject("SELECT inventory_state FROM torrent_downloads WHERE id='a'",String.class)).isEqualTo("ERROR");
            assertThat(jdbc.queryForObject("SELECT inventory_state FROM torrent_downloads WHERE id='b'",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT relative_path FROM torrent_download_files WHERE download_id='c'",String.class)).isEqualTo("cached.epub");
            org.mockito.Mockito.clearInvocations(service,sql);
            for(int n=0;n<100;n++) worker.tick();
            org.mockito.Mockito.verifyNoInteractions(service,sql);
            var adapter=org.mockito.Mockito.mock(TorrentClient.class);
            org.mockito.Mockito.when(adapter.listFiles(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(new TorrentClient.TorrentFile(0,"book.epub",10,1,"/downloads")));
            org.mockito.Mockito.doAnswer(call->{java.util.function.Function<TorrentClient,Object> action=call.getArgument(0);return action.apply(adapter);}).when(service).synchronizeClient(org.mockito.ArgumentMatchers.any());
            clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(1));worker.tick();
            org.mockito.Mockito.verify(adapter,org.mockito.Mockito.times(2)).listFiles(org.mockito.ArgumentMatchers.anyString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE inventory_state='CHECKED'",Long.class)).isEqualTo(2);
        } finally {worker.close();}
    }
    @Test void anInventoryCooldownDoesNotFollowAChangedClientDestination() {
        downloads.deleteAll();
        for(String id:List.of("old","new")) jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,123,1,?,'qbittorrent',?,'DOWNLOADING','EPLSYNC',0)",id,id,id);
        var instance=new java.util.concurrent.atomic.AtomicReference<>("old");
        var source=org.mockito.Mockito.mock(DownloadTrackingService.class);org.mockito.Mockito.when(source.instanceId()).thenAnswer(call->instance.get());
        var service=org.mockito.Mockito.mock(com.rlibanez.eplsync.service.TorrentClientService.class);
        var gate=org.mockito.Mockito.mock(com.rlibanez.eplsync.maintenance.MaintenanceGate.class);org.mockito.Mockito.when(gate.enter(false)).thenReturn(true);
        var worker=new DownloadFileInventory(jdbc,transactions,service,source,gate,new com.rlibanez.eplsync.config.TorrentProperties(),()->0L);
        try {
            org.mockito.Mockito.when(service.synchronizeClient(org.mockito.ArgumentMatchers.any())).thenThrow(new com.rlibanez.eplsync.qbittorrent.QBittorrentConnectionException(com.rlibanez.eplsync.exception.TorrentConnectionException.Reason.UPSTREAM));
            worker.tick();instance.set("new");
            var adapter=org.mockito.Mockito.mock(TorrentClient.class);org.mockito.Mockito.when(adapter.listFiles("new")).thenReturn(List.of(new TorrentClient.TorrentFile(0,"new.epub",10,1,"/downloads")));
            org.mockito.Mockito.doAnswer(call->{java.util.function.Function<TorrentClient,Object> action=call.getArgument(0);return action.apply(adapter);}).when(service).synchronizeClient(org.mockito.ArgumentMatchers.any());
            worker.tick();
            org.mockito.Mockito.verify(service,org.mockito.Mockito.times(2)).synchronizeClient(org.mockito.ArgumentMatchers.any());
            assertThat(jdbc.queryForObject("SELECT inventory_state FROM torrent_downloads WHERE id='new'",String.class)).isEqualTo("CHECKED");
            assertThat(jdbc.queryForObject("SELECT relative_path FROM torrent_download_files WHERE download_id='new'",String.class)).isEqualTo("new.epub");
        } finally {worker.close();}
    }
    @Test void emptyOrInvalidRepliesAndWrongClientCannotEraseAValidInventory() {
        var good=List.of(new TorrentClient.TorrentFile(0,"book.epub",10,1,"/downloads"));
        inventory.record(record,tracking.instanceId(),"A".repeat(40),good);
        inventory.record(record,tracking.instanceId(),"A".repeat(40),List.of());
        inventory.record(record,"other-client","A".repeat(40),List.of(new TorrentClient.TorrentFile(0,"other.pdf",1,1,"/downloads")));
        assertThatThrownBy(()->inventory.record(record,tracking.instanceId(),"A".repeat(40),List.of(new TorrentClient.TorrentFile(0,"../bad.epub",10,1,"/downloads")))).hasMessageContaining("inválido");
        assertThat(jdbc.queryForObject("SELECT relative_path FROM torrent_download_files WHERE download_id=?",String.class,record)).isEqualTo("book.epub");
        assertThat(downloads.findById(record).orElseThrow().getInventoryState()).isEqualTo("CHECKED");
        downloads.deleteById(record);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_download_files",Long.class)).isZero();
    }
    @Test void manyFilesAreStoredInBoundedBatchesAndInvalidFilterIsRejected() {
        var files=new ArrayList<TorrentClient.TorrentFile>();for(int n=0;n<1300;n++) files.add(new TorrentClient.TorrentFile(n,"folder/"+n+".pdf",20,0,"/downloads"));
        inventory.record(record,tracking.instanceId(),"A".repeat(40),files);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_download_files",Long.class)).isEqualTo(1300);
        var filters=new LinkedMultiValueMap<String,String>();filters.set("extensions","../../pdf");assertThatThrownBy(()->queries.search(filters)).hasMessageContaining("Valor inválido");
    }
}
