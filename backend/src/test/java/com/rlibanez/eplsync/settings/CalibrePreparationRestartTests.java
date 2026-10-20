package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.EplsyncApplication;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.EpubFiles;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Two complete application lifecycles sharing one on-disk SQLite database. */
class CalibrePreparationRestartTests {
    @TempDir Path directory;
    @TestConfiguration(proxyBeanMethods=false)
    static class NetworkDoubles {
        @Bean @Primary CalibreClient restartCalibreClient() {
            var client=mock(CalibreClient.class);
            when(client.check(any())).thenReturn(new CalibreClient.Check(true,List.of(new CalibreClient.Library("main","Main")),"main"));return client;
        }
        @Bean @Primary TorrentClientService restartTorrentClient() {
            var client=mock(TorrentClientService.class);when(client.withDefaults(any())).thenAnswer(call->call.getArgument(0));return client;
        }
        @Bean @Primary EpubFiles restartEpubFiles() {return mock(EpubFiles.class);}
    }
    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(EplsyncApplication.class,NetworkDoubles.class).web(WebApplicationType.NONE).run(
            "--spring.datasource.url=jdbc:sqlite:"+directory.resolve("restart.db"),
            "--spring.main.banner-mode=off","--logging.level.root=WARN",
            "--eplsync.security.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "--eplsync.calibre.transfers.worker-enabled=false","--eplsync.torrent.bulk.worker-enabled=false",
            "--eplsync.revision-cleanup.worker-enabled=false","--eplsync.tasks.worker-enabled=false","--eplsync.updates.scheduler-enabled=false");
    }
    private void login(Account owner) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,
            owner.permissions().stream().map(permission->new SimpleGrantedAuthority(permission.name())).toList()));
    }
    @Test void realRestartKeepsTheCommittedBatchAndTheOriginalJobIds() {
        var books=java.util.stream.LongStream.rangeClosed(1,1501).mapToObj(id->new CatalogCalibreTransfers.Book(id,1,"Book "+id)).toList();
        var request=new CatalogCalibreTransfers.Request("restart-request","main",books,List.of(),true,java.util.stream.LongStream.rangeClosed(1,1501).boxed().toList());
        String ownerId,calibreJob,torrentJob;
        try {
            try(var first=start()) {
                var owner=first.getBean(AccountStore.class).initialize("Owner","owner@example.com","password123","password123");ownerId=owner.id();login(owner);
                first.getBean(CalibreSettings.class).save(new CalibreSettings.Input("http://localhost:8081","","",true,"main"));
                var jdbc=first.getBean(JdbcTemplate.class);var rows=new ArrayList<Object[]>();for(long id=1;id<=1501;id++)rows.add(new Object[]{id,"Book "+id,String.format("%040X",id)});
                jdbc.batchUpdate("INSERT INTO catalog_books(epl_id,revision,title,author,links) VALUES(?,1,?,'Author',?)",rows);
                var operations=first.getBean(CalibrePreparationOperations.class);operations.send(request);operations.cycle();
                assertThat(operations.status(request.id()).processed()).isEqualTo(500);
                calibreJob=jdbc.queryForObject("SELECT id FROM calibre_jobs",String.class);torrentJob=jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class);
                assertThat(jdbc.queryForObject("SELECT state FROM calibre_jobs",String.class)).isEqualTo("PAUSED");
                verify(first.getBean(CalibreClient.class),never()).uploadEpub(any(),any(),anyString(),anyString());verify(first.getBean(TorrentClientService.class),never()).addTorrent(any(),any());
            }
            SecurityContextHolder.clearContext();
            try(var second=start()) {
                login(Objects.requireNonNull(second.getBean(AccountStore.class).find(ownerId)));
                var operations=second.getBean(CalibrePreparationOperations.class);var jdbc=second.getBean(JdbcTemplate.class);
                assertThat(operations.status(request.id()).state()).isEqualTo("QUEUED");assertThat(operations.status(request.id()).processed()).isEqualTo(500);
                operations.send(request);for(int batch=0;batch<5 && !operations.status(request.id()).state().equals("COMPLETED");batch++)operations.cycle();
                assertThat(operations.status(request.id()).state()).isEqualTo("COMPLETED");assertThat(jdbc.queryForObject("SELECT id FROM calibre_jobs",String.class)).isEqualTo(calibreJob);assertThat(jdbc.queryForObject("SELECT id FROM torrent_bulk_jobs",String.class)).isEqualTo(torrentJob);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_job_items",Integer.class)).isEqualTo(1501);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_items",Integer.class)).isEqualTo(1501);
                operations.send(request);operations.cycle();assertThat(second.getBean(CatalogCalibreTransfers.class).create(request).id()).isEqualTo(calibreJob);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM calibre_jobs",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM torrent_bulk_jobs",Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
                verify(second.getBean(CalibreClient.class),never()).uploadEpub(any(),any(),anyString(),anyString());verify(second.getBean(TorrentClientService.class),never()).addTorrent(any(),any());
            }
        } finally {SecurityContextHolder.clearContext();}
    }
}
