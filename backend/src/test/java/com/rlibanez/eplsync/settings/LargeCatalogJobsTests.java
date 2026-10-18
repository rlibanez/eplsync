package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.security.AccountStore;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2","eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.revision-cleanup.worker-enabled=false","eplsync.tasks.worker-enabled=false"})
@WithMockUser(authorities={"CATALOG_READ","CALIBRE_SEND","DOWNLOADS_FILES_READ","TORRENT_SEND","TORRENT_SYNC","CALIBRE_JOBS_MANAGE"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LargeCatalogJobsTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("large-jobs.db"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;
    @Autowired CalibreSettings settings;
    @Autowired CatalogCalibreTransfers catalog;
    @Autowired CalibreTransfers transfers;
    @Autowired DownloadTrackingService tracking;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore bulk;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired com.rlibanez.eplsync.config.TorrentProperties torrent;
    @MockitoBean CalibreClient client;
    @MockitoBean EpubFiles files;
    @MockitoBean TorrentClientService torrentClient;

    @Test void wholeCatalogCreatesSingleLinkedJobsAndDoesNotLimitTheGlobalCalibreQueue() {
        var owner=accounts.initialize("Owner","owner@example.com","password123","password123");
        var actor=new EventContext.Actor(owner.id(),owner.username(),"ADMIN");
        settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"main"));
        when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main"));
        when(torrentClient.withDefaults(any())).thenAnswer(call->call.getArgument(0));
        torrent.effective().setEnabled(true);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(status->{
            for(int start=1;start<=73004;start+=500) {
                var batch=new ArrayList<Object[]>();
                for(int id=start;id<Math.min(start+500,73005);id++) batch.add(new Object[]{id,1.0,"Book "+id,id<=73002?"Author":"Other",String.format("%040X",id)});
                jdbc.batchUpdate("INSERT INTO catalog_books(epl_id,revision,title,author,links) VALUES(?,?,?,?,?)",batch);
            }
        });
        var filter=new CatalogBookFilter();filter.setExcludedIds(new Long[]{73001L,73002L});filter.setAuthor("Author");
        var prepared=catalog.prepare(new CatalogCalibreTransfers.Selection(filter));
        assertThat(prepared.books()).hasSize(73000);assertThat(prepared.missing()).hasSize(73000);
        var request=new CatalogCalibreTransfers.Request("whole-catalog","main",prepared.books(),List.of(),true,prepared.missing().stream().map(book -> Objects.requireNonNull(book).eplId()).toList());
        var job=EventContext.withActor(actor,()->catalog.create(request));
        assertThat(job.total()).isEqualTo(73000);assertThat(job.pending()).isEqualTo(73000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items",Integer.class)).isEqualTo(73000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE epl_id >= 73001",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items WHERE epl_id >= 73001",Integer.class)).isZero();
        String torrentJob=jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class);
        bulk.control(torrentJob,"pause");bulk.recover();
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id=?",String.class,torrentJob)).isEqualTo("PAUSED");
        bulk.control(torrentJob,"resume");
        jdbc.update("UPDATE torrent_bulk_jobs SET state='RUNNING' WHERE id=?",torrentJob);
        jdbc.update("UPDATE torrent_bulk_items SET state='IN_FLIGHT' WHERE job_id=? AND epl_id=1",torrentJob);
        bulk.recover();
        assertThat(jdbc.queryForObject("SELECT state FROM torrent_bulk_jobs WHERE id=?",String.class,torrentJob)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items WHERE state='PENDING'",Integer.class)).isEqualTo(73000);
        transfers.control(job.id(),"pause");transfers.recoverInterrupted();
        assertThat(transfers.job(job.id()).state()).isEqualTo("PAUSED");
        transfers.control(job.id(),"resume");
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",job.id());
        transfers.recoverInterrupted();
        assertThat(transfers.job(job.id()).state()).isEqualTo("QUEUED");
        assertThat(EventContext.withActor(actor,()->catalog.create(request)).id()).isEqualTo(job.id());
        assertThat(jdbc.queryForObject("SELECT length(payload) FROM calibre_update_requests WHERE id='catalog:whole-catalog'",Integer.class)).isLessThan(100);
        // Already-downloaded selections are queued locally even when the queue exceeds 10000.
        tx.executeWithoutResult(status->{
            var rows=new ArrayList<Object[]>();
            for(long id=1;id<=1001;id++) rows.add(new Object[]{"record-"+id,id,1.0,String.format("%040X",id),tracking.instanceId()});
            jdbc.batchUpdate("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES(?,?,?,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0)",rows);
        });
        var selected=new CatalogBookFilter();selected.setSelectedIds(java.util.stream.LongStream.rangeClosed(1,1001).boxed().toArray(Long[]::new));
        var available=catalog.prepare(new CatalogCalibreTransfers.Selection(selected));
        assertThat(available.files()).isEmpty();assertThat(available.deferred()).hasSize(1001);
        var deferred=new CatalogCalibreTransfers.Request("available","main",available.books(),List.of(),false,available.deferred().stream().map(book -> Objects.requireNonNull(book).eplId()).toList());
        var deferredJob=EventContext.withActor(actor,()->catalog.create(deferred));
        assertThat(deferredJob.total()).isEqualTo(1001);
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,status,origin,created_at) VALUES('record-extra',1,2,?,'qbittorrent',?,'DOWNLOADED','EPLSYNC',0)","F".repeat(40),tracking.instanceId());
        var ids=new ArrayList<>(java.util.stream.LongStream.rangeClosed(1,1001).mapToObj(id->"record-"+id).toList());ids.add("record-extra");
        assertThat(transfers.prepare(ids).deferredRecords()).hasSize(1002);
        jdbc.update("UPDATE torrent_downloads SET status='DOWNLOADING' WHERE id='record-extra'");
        var mixed=new ArrayList<>(ids);mixed.add("removed-record");
        var partial=transfers.prepare(mixed);
        assertThat(partial.deferredRecords()).hasSize(1001).doesNotContain("record-extra");
        assertThat(partial.unavailable()).hasSize(2);
        jdbc.update("UPDATE torrent_downloads SET status='DOWNLOADED' WHERE id='record-extra'");
        assertThat(EventContext.withActor(actor,()->transfers.create(new CalibreTransfers.Request("main",List.of(),ids))).total()).isEqualTo(1002);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isEqualTo(75003);
        verify(files,never()).list(anyString());verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        // Process a deferred batch using the existing worker, then upload only one EPUB.
        jdbc.update("UPDATE calibre_jobs SET state='PAUSED'");jdbc.update("UPDATE calibre_jobs SET state='QUEUED' WHERE id=?",deferredJob.id());
        var payloads=new HashMap<String,byte[]>();
        when(files.list(anyString())).thenAnswer(call->{String record=call.getArgument(0);long id=Long.parseLong(record.substring(7));var bytes=CalibreTransfersTests.epub(id,"1");payloads.put(record,bytes);return List.of(new EpubFiles.Item(0,id+".epub",bytes.length));});
        when(files.download(anyString(),eq(0))).thenAnswer(call->{String record=call.getArgument(0);var bytes=payloads.get(record);return new EpubFiles.Opened(record.substring(7)+".epub",bytes.length,new java.io.ByteArrayInputStream(bytes));});
        when(client.uploadEpub(any(),any(),anyString(),anyString())).thenReturn(9L);
        when(client.book(any(),eq(9L))).thenReturn(new CalibreClient.Book(9,"Book 1",List.of("Author"),1L,java.math.BigDecimal.ONE,true));
        when(client.hasEpub(any(),eq(9L))).thenReturn(true);
        transfers.cycle();verify(client,never()).uploadEpub(any(),any(),anyString(),anyString());
        transfers.cycle();assertThat(transfers.job(deferredJob.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        // A crash after the remote upload must verify the persisted ID, never upload it twice.
        jdbc.update("UPDATE calibre_job_items SET state='SENDING' WHERE job_id=? AND epl_id=1",deferredJob.id());
        jdbc.update("UPDATE calibre_jobs SET state='RUNNING' WHERE id=?",deferredJob.id());
        transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(deferredJob.id()).accepted()).isEqualTo(1);
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        transfers.control(deferredJob.id(),"pause");transfers.recoverInterrupted();transfers.cycle();
        assertThat(transfers.job(deferredJob.id()).state()).isEqualTo("PAUSED");
        verify(client,times(1)).uploadEpub(any(),any(),anyString(),anyString());
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        torrent.effective().setEnabled(false);
    }
}
