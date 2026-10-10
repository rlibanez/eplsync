package com.rlibanez.eplsync.performance;

import com.rlibanez.eplsync.importer.CatalogBookCsvImporter;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.CatalogBookService;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.controller.CatalogDirectoryController;
import com.rlibanez.eplsync.controller.HomeController;
import com.rlibanez.eplsync.torrent.downloads.DownloadQueryService;
import com.rlibanez.eplsync.torrent.updates.RevisionUpdates;
import com.rlibanez.eplsync.security.UpdatePreferences;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, real SQLite/WAL benchmark. No production data or network requests. */
@EnabledIfSystemProperty(named="eplsync.test.performance", matches="true")
@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","TORRENT_SYNC","TORRENT_JOBS_MANAGE"})
@SpringBootTest(properties={"spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
    "eplsync.torrent.bulk.worker-enabled=false","eplsync.torrent.cleanup.enabled=false",
    "eplsync.torrent.bulk.retention.enabled=false"})
class DatabasePerformanceTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+directory.resolve("performance.db"));
        r.add("spring.datasource.hikari.maximum-pool-size",()->2);
    }
    @Autowired CatalogBookCsvImporter importer;
    @Autowired CatalogBookRepository books;
    @Autowired CatalogBookService catalog;
    @Autowired CatalogDirectoryController directories;
    @Autowired HomeController home;
    @Autowired DownloadQueryService downloads;
    @Autowired RevisionUpdates updates;
    @Autowired com.rlibanez.eplsync.torrent.bulk.BulkStore jobs;
    @Autowired com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService tracking;
    @Autowired EntityManagerFactory factory;
    @Autowired JdbcTemplate jdbc;
    @FunctionalInterface interface Operation { void run() throws Exception; }
    void measure(String name, Operation action) throws Exception {
        var stats=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.clear();
        var peak=new java.util.concurrent.atomic.AtomicLong();
        try(var sampler=Executors.newSingleThreadScheduledExecutor()) {
            var heap=java.lang.management.ManagementFactory.getMemoryMXBean();
            var sampling=sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(heap.getHeapMemoryUsage().getUsed(),Math::max),0,10,TimeUnit.MILLISECONDS);
            long start=System.nanoTime();
            try {action.run();} finally {
                sampling.cancel(false);
                System.out.printf(Locale.ROOT,"PERF %s seconds=%.3f SQL=%d sampled_heap_MiB=%.1f%n",name,(System.nanoTime()-start)/1e9,stats.getPrepareStatementCount(),peak.get()/1048576.0);
            }
        }
    }
    Path csv(String filename, boolean changed) throws Exception {
        Path path=directory.resolve(filename);
        try(var out=Files.newBufferedWriter(path)) {
            out.write("EPL Id,Revisión,Autor,Título,Sinopsis,Publicado,Idioma\n");
            String synopsis="Sinopsis de prueba. ".repeat(50);
            for(int id=1;id<=73000;id++) out.write(id+","+(changed && id%10==0 ? "2" : "1")+",Autor "+(id%1000)+",Libro "+String.format(Locale.ROOT,"%06d",id)+","+synopsis+","+(id%2==0?"P":"A")+":"+String.format(Locale.ROOT,"%02d-02-2026",id%28+1)+",Español\n");
        }
        return path;
    }
    void query(String name,Operation action) throws Exception {
        action.run(); // Warm the ORM/query plan once; print each of three repetitions, not an unstable assertion.
        for(int n=1;n<=3;n++) measure(name+"-"+n,action);
    }
    @Test
    @org.junit.jupiter.api.Timeout(value=5, unit=TimeUnit.MINUTES)
    void profileImportsQueriesAndConcurrentReaders() throws Exception {
        var original=csv("original.csv",false); var changed=csv("changed.csv",true);
        measure("import-new",()->assertThat(importer.importFile(original,true).created()).isEqualTo(73000));
        measure("import-unchanged",()->assertThat(importer.importFile(original,false).unchanged()).isEqualTo(73000));
        measure("import-changed-10-percent",()->assertThat(importer.importFile(changed,false).updated()).isEqualTo(7300));
        measure("preview",()->assertThat(importer.previewFile(changed,0,50).summary().recordsUnchanged()).isEqualTo(73000));
        assertThat(books.count()).isEqualTo(73000);
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,origin,status,created_at,submitted_at) SELECT 'old-'||epl_id,epl_id,0.8,printf('%040X',1000000+epl_id),'qbittorrent','benchmark','EPLSYNC','DOWNLOADED',1,1 FROM catalog_books");
        jdbc.update("INSERT INTO torrent_downloads(id,epl_id,revision,hash,client,client_instance_id,origin,status,created_at,submitted_at) SELECT 'current-'||epl_id,epl_id,1,printf('%040X',epl_id),'qbittorrent','benchmark','EPLSYNC','DOWNLOADED',2,2 FROM catalog_books");
        // Completed jobs still participate in the anti-join: prevent unrealistic empty-table plans.
        jdbc.update("INSERT INTO torrent_bulk_jobs(id,batch_size,concurrency,interval_millis,selected_books,state) VALUES('benchmark',100,1,500,73000,'COMPLETED')");
        jdbc.update("INSERT INTO torrent_bulk_items(id,job_id,epl_id,revision,position,attempts,state,command_json) SELECT 'item-'||epl_id,'benchmark',epl_id,revision,epl_id,0,'ACCEPTED','{\"book\":{\"revision\":1}}' FROM catalog_books");
        query("catalog-title",()->assertThat(catalog.search(new CatalogBookFilter(),PageRequest.of(0,50,Sort.by("title"))).getTotalElements()).isEqualTo(73000));
        query("catalog-deep",()->assertThat(catalog.search(new CatalogBookFilter(),PageRequest.of(1000,50,Sort.by("title"))).getContent()).hasSize(50));
        var published=new CatalogBookFilter();published.setPublicationStatus(com.rlibanez.eplsync.model.enums.PublicationStatus.PUBLISHED);
        query("home-new-books",()->catalog.search(published,PageRequest.of(0,50,Sort.by(Sort.Direction.DESC,"publicationDate","eplId"))));
        query("catalog-added",()->catalog.search(new CatalogBookFilter(),PageRequest.of(0,50,Sort.by(Sort.Direction.DESC,"insertDate"))));
        var filter=new CatalogBookFilter(); filter.setTitle("000");
        query("catalog-text",()->catalog.search(filter,PageRequest.of(0,50,Sort.by("title"))));
        query("directory-authors",()->directories.list("authors","",0,50));
        query("home-summary",()->home.summary(new org.springframework.mock.web.MockHttpServletResponse()));
        var params=new LinkedMultiValueMap<String,String>();params.add("size","50");
        query("downloads",()->downloads.search(params));
        query("jobs",()->jobs.list(0,50,null));
        query("revision-updates",()->assertThat(updates.search(new UpdatePreferences.Preferences(List.of(DownloadStatus.DOWNLOADED)),0,50,"title,asc").meta().totalItems()).isEqualTo(7300));
        for(String sql:List.of(
            "SELECT epl_id FROM catalog_books WHERE publication_status='PUBLISHED' ORDER BY publication_date DESC,epl_id DESC LIMIT 50",
            "SELECT epl_id FROM catalog_books ORDER BY cast((insert_date - ((insert_date % 60000 + 60000) % 60000)) / 60000 as integer) DESC,epl_id ASC LIMIT 50",
            "SELECT id FROM torrent_bulk_items WHERE epl_id=73000 AND state IN ('PENDING','IN_FLIGHT')")) {
            System.out.println("PERF PLAN "+sql+" => "+jdbc.queryForList("EXPLAIN QUERY PLAN "+sql));
        }
        try(var pool=Executors.newFixedThreadPool(3)) {
            var start=new CountDownLatch(1);
            var writer=pool.submit(()->{start.await();return importer.importFile(original,false);});
            var readers=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++) readers.add(pool.submit(()->{start.await();int count=0;for(int n=0;n<20;n++) {assertThat(catalog.search(new CatalogBookFilter(),PageRequest.of(0,50,Sort.by("title"))).getContent()).hasSize(50);count++;}return count;}));
            long before=System.nanoTime();start.countDown();
            assertThat(writer.get(120,TimeUnit.SECONDS).updated()).isEqualTo(7300);
            for(var reader:readers) assertThat(reader.get(120,TimeUnit.SECONDS)).isEqualTo(20);
            System.out.printf(Locale.ROOT,"PERF concurrent-import-two-readers seconds=%.3f%n",(System.nanoTime()-before)/1e9);
        }
        jdbc.update("UPDATE torrent_downloads SET client_instance_id=?", tracking.instanceId());
        var remote=new ArrayList<com.rlibanez.eplsync.torrent.downloads.RemoteTorrent>(146000);
        for(int id=1;id<=73000;id++) {
            remote.add(new com.rlibanez.eplsync.torrent.downloads.RemoteTorrent(String.format(Locale.ROOT,"%040X",id),DownloadStatus.DOWNLOADED,java.time.Instant.ofEpochMilli(1000)));
            remote.add(new com.rlibanez.eplsync.torrent.downloads.RemoteTorrent(String.format(Locale.ROOT,"%040X",1000000+id),DownloadStatus.DOWNLOADED,java.time.Instant.ofEpochMilli(1000)));
        }
        try(var pool=Executors.newFixedThreadPool(3)) {
            var start=new CountDownLatch(1);
            var writer=pool.submit(()->{start.await();return tracking.sync(()->remote,false,false);});
            var readers=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++) readers.add(pool.submit(()->{start.await();int count=0;for(int n=0;n<20;n++) {assertThat(catalog.search(new CatalogBookFilter(),PageRequest.of(0,50,Sort.by("title"))).getContent()).hasSize(50);count++;}return count;}));
            long before=System.nanoTime();start.countDown();
            assertThat(writer.get(120,TimeUnit.SECONDS).records().checked()).isEqualTo(146000);
            for(var reader:readers) assertThat(reader.get(120,TimeUnit.SECONDS)).isEqualTo(20);
            System.out.printf(Locale.ROOT,"PERF concurrent-sync-two-readers seconds=%.3f%n",(System.nanoTime()-before)/1e9);
        }

    }
}
