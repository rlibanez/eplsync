package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.events.EventContext;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.security.AccountStore;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentDownload;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real workers, JPA/JDBC and a two-connection WAL database; only network/file adapters are simulated. */
@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2",
    "eplsync.calibre.transfers.worker-enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@WithMockUser(authorities={"ROLE_ADMIN","CALIBRE_SEND","DOWNLOADS_FILES_READ"})
class CalibreTorrentConcurrencyTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("transfers.db"));
    }
    @Autowired CalibreTransfers transfers;
    @Autowired CalibreSettings settings;
    @Autowired CalibreCatalogService library;
    @Autowired AccountStore accounts;
    @Autowired CatalogBookRepository books;
    @Autowired DownloadRepository downloads;
    @Autowired DownloadTrackingService tracking;
    @Autowired TorrentProperties properties;
    @Autowired BulkStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource source;
    @Autowired RevisionCleanup cleanup;
    @Autowired JobRetries retries;
    @Autowired com.rlibanez.eplsync.events.EventJournal journal;
    @Autowired BulkRetention retention;
    @MockitoBean CalibreClient calibre;
    @MockitoBean TorrentClientService torrent;
    @MockitoBean EpubFiles files;

    @Test void thousandSendsAndUploadsWithFourReadersAndSyncsDoNotDuplicateOperations() throws Exception {
        var owner=accounts.initialize("Owner","owner@example.com","password123","password123");
        settings.save(new CalibreSettings.Input("http://localhost:8081","","",true,"main"));
        properties.effective().setEnabled(true);
        var choices=new ArrayList<CalibreTransfers.FileChoice>();
        var payloads=new HashMap<String,byte[]>();
        var identities=new HashMap<String,Long>();
        var commands=new ArrayList<TorrentDownload>();
        for(long id=1;id<=1000;id++) {
            books.save(CatalogBook.builder().eplId(id).revision(1.0).title("Book "+id).author("Author").build());
            var row=new DownloadRecord();row.setEplId(id);row.setRevision(1.0);row.setHash(String.format("%040X",id));
            row.setClient("qbittorrent");row.setClientInstanceId(tracking.instanceId());row.setStatus(DownloadStatus.DOWNLOADED);
            row.setOrigin(DownloadRecord.Origin.EPLSYNC);row.setCreatedAt(Instant.now());
            var record=downloads.save(row).getId();var bytes=CalibreTransfersTests.epub(id,"1");
            payloads.put(record,bytes);identities.put(record,id);choices.add(new CalibreTransfers.FileChoice(record,0));
            var pending=books.save(CatalogBook.builder().eplId(id+1000).revision(1.0).title("Torrent "+id).author("Author").build());
            commands.add(new TorrentDownload(String.format("%040X",id+1000),"magnet:?xt=urn:btih:"+String.format("%040X",id+1000),true,null,null,null,pending));
        }
        when(files.list(anyString())).thenAnswer(call -> {String id=call.getArgument(0);return List.of(new EpubFiles.Item(0,identities.get(id)+".epub",payloads.get(id).length));});
        when(files.download(anyString(),eq(0))).thenAnswer(call -> {String id=call.getArgument(0);var bytes=payloads.get(id);return new EpubFiles.Opened(identities.get(id)+".epub",bytes.length,new java.io.ByteArrayInputStream(bytes));});
        when(calibre.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main"));
        var uploaded=ConcurrentHashMap.<Long>newKeySet();var uploadRequests=ConcurrentHashMap.<String>newKeySet();
        var contention=new ArrayList<CompletableFuture<Void>>();
        var added=ConcurrentHashMap.<String>newKeySet();var inFlight=new AtomicInteger();var overlap=new AtomicInteger();
        when(calibre.hasEpub(any(),anyLong())).thenReturn(true);
        when(calibre.findRevision(any(),anyLong(),any())).thenAnswer(call -> {Long id=call.getArgument(1);return uploaded.contains(id)?id:null;});
        when(calibre.book(any(),anyLong())).thenAnswer(call -> {long id=call.getArgument(1);return new CalibreClient.Book(id,"Book "+id,List.of("Author"),id,BigDecimal.ONE,true);});
        when(calibre.uploadEpub(any(),any(),anyString(),anyString())).thenAnswer(call -> {
            long id=Long.parseLong(((String)call.getArgument(2)).replace(".epub",""));
            assertThat(uploadRequests.add(call.getArgument(3))).isTrue();assertThat(uploaded.add(id)).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(inFlight.get()>0) overlap.incrementAndGet();
            if(id==1) {
                poisonReturnedConnection();
                // The remote upload succeeded. Hold a competing SQLite write while
                // both workers try to persist their local results, without replaying HTTP.
                var locked=new CountDownLatch(1);var done=new CompletableFuture<Void>();contention.add(done);
                Thread.ofVirtual().start(()-> {
                    try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("transfers.db"));
                            var statement=connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout=10000");connection.setAutoCommit(false);
                        statement.executeUpdate("UPDATE calibre_jobs SET updated_at=updated_at");
                        locked.countDown();Thread.sleep(200);connection.commit();done.complete(null);
                    } catch(Throwable ex) {done.completeExceptionally(ex);locked.countDown();}
                });
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            }
            Thread.sleep(3);return id;
        });
        when(torrent.addTorrent(any(),any())).thenAnswer(call -> {
            TorrentDownload command=call.getArgument(0);
            return tracking.submit(command,()-> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                inFlight.incrementAndGet();
                try {Thread.sleep(15);assertThat(added.add(command.hash())).isTrue();if(command.hash().equals(String.format("%040X",1001))) poisonReturnedConnection();return TorrentDownloadResult.Status.ACCEPTED;}
                catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                finally {inFlight.decrementAndGet();}
            });
        });
        var sources=new ConcurrentHashMap<String,RemoteTorrent>();
        for(long id=1;id<=1000;id++) sources.put(String.format("%040X",id),new RemoteTorrent(String.format("%040X",id),DownloadStatus.DOWNLOADED,Instant.now(),Set.of(),"/downloads/"+id+".epub","Book","/downloads",false));
        java.util.function.Supplier<List<RemoteTorrent>> remoteSnapshot=()->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var result=new ArrayList<>(sources.values());
            for(var hash:added) result.add(new RemoteTorrent(hash,DownloadStatus.SUBMITTED,null,Set.of(),"/downloads/pending-"+hash+".epub","Pending","/downloads",false));
            return result;
        };
        var syncActive=new AtomicInteger();var syncMaximum=new AtomicInteger();
        when(calibre.books(any(),anyInt(),eq(100))).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            syncMaximum.accumulateAndGet(syncActive.incrementAndGet(),Math::max);
            try {
                Thread.sleep(5);int offset=(int)call.getArgument(1)*100;
                var ids=uploaded.stream().sorted().toList();
                return new CalibreClient.Books(ids.stream().skip(offset).limit(100).map(id->new CalibreClient.Book(id,"Book "+id,List.of("Author"),id,BigDecimal.ONE,true)).toList(),ids.size(),offset/100,100);
            }finally {syncActive.decrementAndGet();}
        });
        var actor=new EventContext.Actor(owner.id(),owner.username(),"ADMIN");
        var calibreJob=EventContext.withActor(actor,()->transfers.create(new CalibreTransfers.Request("main",choices)));
        var torrentJob=EventContext.withActor(actor,()->store.createPrepared(commands,new BulkRequest(null,100,5,"0ms")));
        var worker=new BulkWorker(store,torrent,properties);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var stopReaders=new java.util.concurrent.atomic.AtomicBoolean();
            var readerChecks=new AtomicInteger();
            var readers=new ArrayList<Future<?>>();
            for(int reader=0;reader<4;reader++) readers.add(executor.submit(()->{
                start.await();
                while(!stopReaders.get()) {
                    var counts=jdbc.queryForMap("SELECT count(*) total,count(DISTINCT calibre_id) unique_ids FROM calibre_books WHERE active=true");
                    assertThat(((Number)counts.get("total")).longValue()).isEqualTo(((Number)counts.get("unique_ids")).longValue()).isBetween(0L,1000L);
                    jdbc.queryForObject("SELECT count(*) FROM torrent_downloads",Long.class);
                    readerChecks.incrementAndGet();Thread.sleep(3);
                }
                return null;
            }));
            try {
            worker.recover();
            var torrentSync=executor.submit(()->{start.await();for(int n=0;n<3;n++) tracking.sync(remoteSnapshot,false,false);return null;});
            var calibreSync=executor.submit(()->{start.await();for(int n=0;n<3;n++) library.sync();return null;});
            var sending=executor.submit(()-> {start.await();for(int n=0;n<1000;n++) transfers.cycle();return null;});
            var maintenance=executor.submit(()-> {
                start.await();
                for(int n=0;n<20;n++) {transfers.retention();cleanup.retention();retries.expirePreviews();journal.retention();retention.tick();Thread.sleep(5);}
                return null;
            });
            start.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(!store.view(torrentJob.jobId()).status().equals(BulkJob.State.COMPLETED) && System.nanoTime()<deadline) {worker.tick();Thread.sleep(2);}
            sending.get(90,TimeUnit.SECONDS);maintenance.get(90,TimeUnit.SECONDS);
            torrentSync.get(90,TimeUnit.SECONDS);calibreSync.get(90,TimeUnit.SECONDS);
            assertThat(syncMaximum.get()).isEqualTo(1);assertThat(readerChecks.get()).isGreaterThan(4);
            for(var held:contention) held.get(5,TimeUnit.SECONDS);
            assertThat(store.view(torrentJob.jobId()).status()).isEqualTo(BulkJob.State.COMPLETED);
            assertThat(store.view(torrentJob.jobId()).accepted()).isEqualTo(1000);
            assertThat(transfers.job(calibreJob.id()).state()).isEqualTo("COMPLETED");
            assertThat(transfers.job(calibreJob.id()).accepted()).isEqualTo(1000);
            assertThat(added).hasSize(1000);assertThat(uploaded).hasSize(1000);assertThat(overlap.get()).isPositive();
            worker.tick();transfers.cycle();
            verify(torrent,times(1000)).addTorrent(any(),any());verify(calibre,times(1000)).uploadEpub(any(),any(),anyString(),anyString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_books WHERE active=true",Integer.class)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_downloads WHERE status='SUBMITTED'",Integer.class)).isEqualTo(1000);
            assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
            // The same recovered pool must handle remote deletions too, including
            // loss of a connection after delete HTTP succeeded but before recording it.
            var adapter=mock(com.rlibanez.eplsync.torrent.TorrentClient.class);
            when(adapter.listTorrents()).thenAnswer(call->remoteSnapshot.get());
            when(adapter.listTorrents(anySet())).thenAnswer(call->remoteSnapshot.get());
            var deletions=new AtomicInteger();
            doAnswer(call->{String hash=call.getArgument(0);assertThat(sources.remove(hash)).isNotNull();if(deletions.incrementAndGet()==1) poisonReturnedConnection();return null;}).when(adapter).deleteTorrent(anyString(),eq(true));
            when(torrent.exclusiveClient(any())).thenAnswer(call->{java.util.function.Function<com.rlibanez.eplsync.torrent.TorrentClient,?> work=call.getArgument(0);return tracking.exclusive(()->work.apply(adapter));});
            var previous=org.springframework.security.core.context.SecurityContextHolder.getContext();
            var security=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner,null,owner.permissions().stream().map(p->new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())).toList()));
            org.springframework.security.core.context.SecurityContextHolder.setContext(security);
            try {
                var ids=choices.subList(0,6).stream().map(choice -> choice.recordId()).toList();
                var removal=cleanup.removeSelected(new RevisionCleanup.RemovalInput(UUID.randomUUID().toString(),new com.rlibanez.eplsync.torrent.updates.HistoryActions.Removal(null,ids,true,"files",true,true)));
                var syncWhileDeleting=executor.submit(()->library.sync());
                var torrentWhileDeleting=executor.submit(()->tracking.sync(remoteSnapshot,false,false));
                cleanup.cycle();
                syncWhileDeleting.get(10,TimeUnit.SECONDS);torrentWhileDeleting.get(10,TimeUnit.SECONDS);
                cleanup.cycle();cleanup.recover();cleanup.cycle();worker.tick();transfers.cycle();
                assertThat(cleanup.get(removal.id()).succeeded()).as("%s",cleanup.items(removal.id(),0,20).items()).isEqualTo(6);
                assertThat(cleanup.get(removal.id()).blocked()).isZero();
                assertThat(deletions.get()).isEqualTo(6);
                verify(adapter,times(6)).deleteTorrent(anyString(),eq(true));
                verify(torrent,times(1000)).addTorrent(any(),any());
                verify(calibre,times(1000)).uploadEpub(any(),any(),anyString(),anyString());
            } finally {org.springframework.security.core.context.SecurityContextHolder.setContext(previous);}
            assertThat(jdbc.queryForObject("PRAGMA integrity_check",String.class)).isEqualTo("ok");
            } finally {start.countDown();stopReaders.set(true);for(var reader:readers) reader.get(5,TimeUnit.SECONDS);}
        } finally {worker.close();properties.effective().setEnabled(false);}
    }
    private void poisonReturnedConnection() {
        try(var pooled=source.getConnection()) {
            pooled.setAutoCommit(false);
            try(var statement=pooled.createStatement();var result=statement.executeQuery("SELECT 1")) {assertThat(result.next()).isTrue();}
            pooled.unwrap(org.sqlite.SQLiteConnection.class).getDatabase().exec("COMMIT",false);
            assertThatThrownBy(pooled::close).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("no transaction is active");
        } catch(java.sql.SQLException ex) {throw new IllegalStateException(ex);}
    }

}
