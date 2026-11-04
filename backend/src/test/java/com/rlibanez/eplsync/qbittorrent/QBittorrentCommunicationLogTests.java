package com.rlibanez.eplsync.qbittorrent;

import java.net.ConnectException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class QBittorrentCommunicationLogTests {
    final AtomicLong clock=new AtomicLong();
    final Logger logger=(Logger)LoggerFactory.getLogger("qbit-communication-test-"+UUID.randomUUID());
    final ListAppender<ILoggingEvent> capture=new ListAppender<>();
    final QBittorrentCommunicationLog diagnostics=new QBittorrentCommunicationLog(logger,clock::get);
    @BeforeEach void setup() {logger.setLevel(Level.DEBUG);logger.setAdditive(false);capture.start();logger.addAppender(capture);}
    @AfterEach void close() {logger.detachAppender(capture);capture.stop();}
    @Test void repeatedFailuresAreBoundedAndNeverLogCredentialsOrExceptionMessages() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<1000;n++) tasks.add(executor.submit(()->diagnostics.failed("GET","/api/v2/torrents/info",new ConnectException("password=secret-token"))));
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
        }
        assertThat(capture.list).hasSize(1);
        assertThat(capture.list.getFirst().getFormattedMessage()).contains("GET /api/v2/torrents/info","ConnectException","fallosDesdeUltimoAviso=1").doesNotContain("secret-token","password");
        clock.set(TimeUnit.MINUTES.toNanos(1));diagnostics.failed("POST","/api/v2/torrents/add",new ConnectException());
        assertThat(capture.list).hasSize(2);
        assertThat(capture.list.getLast().getFormattedMessage()).contains("POST /api/v2/torrents/add","fallosDesdeUltimoAviso=1000");
    }
}
