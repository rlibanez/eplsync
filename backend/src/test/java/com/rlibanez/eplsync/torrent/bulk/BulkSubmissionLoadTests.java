package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadResult;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.TorrentDownload;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"eplsync.tasks.worker-enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false","eplsync.torrent.inventory.worker-enabled=false","eplsync.calibre.transfers.worker-enabled=false","eplsync.updates.scheduler-enabled=false","spring.datasource.hikari.maximum-pool-size=4"})
class BulkSubmissionLoadTests {
    @TempDir static java.nio.file.Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {registry.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("load.db"));}
    @Autowired JdbcTemplate jdbc;
    @Autowired BulkStore store;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired TorrentProperties properties;
    @Autowired DownloadTrackingService tracking;
    @MockitoBean TorrentClientService client;

    @Test @Timeout(180)
    void sixtyFiveThousandPendingRowsKeepFourReadersResponsiveWhileSubmissionWritesProgress() throws Exception {
        int queued=65000,target=2000;String id=UUID.randomUUID().toString();var mapper=JsonMapper.builder().build();
        properties.effective().setEnabled(true);
        String fingerprint=tracking.instanceId();long now=System.currentTimeMillis();
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,state,type,client,target_fingerprint,multiple_hashes,batch_size,concurrency,interval_millis,selected_books,created_at,updated_at) VALUES(?,'QUEUED','DOWNLOAD',?,?,'ALL',100,5,0,?,?,?)",id,properties.getClient(),fingerprint,queued,now,now);
        for(int offset=0;offset<queued;offset+=500) {
            var batch=new ArrayList<Object[]>();
            for(int n=offset+1;n<=Math.min(queued,offset+500);n++) {
                String hash=String.format(Locale.ROOT,"%040X",n);var book=CatalogBook.builder().eplId((long)n).title("Book "+n).revision(1.0).language(Language.INGLES).build();
                var command=new TorrentDownload(hash,"magnet:?xt=urn:btih:"+hash,true,null,"Test",null,book);
                batch.add(new Object[]{UUID.randomUUID().toString(),id,n,n,hash,mapper.writeValueAsString(command)});
            }
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->jdbc.batchUpdate("INSERT INTO torrent_bulk_items(id,job_id,position,epl_id,hash,revision,state,attempts,command_json) VALUES(?,?,?,?,?,1,'PENDING',0,?)",batch));
        }
        var seen=new ConcurrentHashMap<String,AtomicInteger>();
        when(client.prioritizeSubmissions()).thenAnswer(call->tracking.prioritizeSubmissions());
        when(client.addTorrent(any(),any())).thenAnswer(call->{
            TorrentDownload command=call.getArgument(0);
            return tracking.submit(command,()->{seen.computeIfAbsent(command.hash(),key->new AtomicInteger()).incrementAndGet();return TorrentDownloadResult.Status.ACCEPTED;});
        });
        var stop=new AtomicBoolean();var reads=new AtomicInteger();var slowest=new AtomicLong();var failures=new ConcurrentLinkedQueue<Throwable>();
        try(var readers=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<?>>();
            for(int n=0;n<4;n++) tasks.add(readers.submit(()->{
                while(!stop.get()) {
                    long started=System.nanoTime();
                    try {assertThat(jdbc.queryForObject("SELECT selected_books FROM torrent_bulk_jobs WHERE id=?",Integer.class,id)).isEqualTo(queued);reads.incrementAndGet();}
                    catch(Throwable ex) {failures.add(ex);return;}
                    slowest.accumulateAndGet(System.nanoTime()-started,Math::max);
                    try {Thread.sleep(10);}catch(InterruptedException ex) {Thread.currentThread().interrupt();return;}
                }
            }));
            var worker=new BulkWorker(store,client,properties);long started=System.nanoTime();
            try {
                worker.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
                while(seen.size()<target && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(seen.size()).isGreaterThanOrEqualTo(target);
                synchronized(store) {store.control(id,"pause");}
                while(worker.hasInFlightSends() && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(worker.hasInFlightSends()).isFalse();assertThat(tracking.inventoryAllowed()).isTrue();
                assertThat(store.view(id).accepted()).isEqualTo(seen.size());assertThat(seen.values()).allSatisfy(value->assertThat(value).hasValue(1));
                System.out.printf(Locale.ROOT,"SQLite WAL load result: queued=%d sent=%d readers=4 reads=%d maxReadMs=%d seconds=%.3f%n",queued,seen.size(),reads.get(),TimeUnit.NANOSECONDS.toMillis(slowest.get()),(System.nanoTime()-started)/1_000_000_000.0);
            } finally {worker.close();stop.set(true);properties.effective().setEnabled(false);}
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
        }
        assertThat(failures).isEmpty();assertThat(reads.get()).isGreaterThan(100);assertThat(TimeUnit.NANOSECONDS.toMillis(slowest.get())).isLessThan(2000);
    }
}
