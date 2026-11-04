package com.rlibanez.eplsync.torrent.downloads;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class TorrentAccessWaitLogTests {
    final AtomicLong clock=new AtomicLong();
    final Logger logger=(Logger)LoggerFactory.getLogger("torrent-wait-test-"+UUID.randomUUID());
    final ListAppender<ILoggingEvent> capture=new ListAppender<>();
    final TorrentAccessWaitLog diagnostics=new TorrentAccessWaitLog(logger,clock::get);
    @BeforeEach void setup() {logger.setLevel(Level.DEBUG);logger.setAdditive(false);capture.start();logger.addAppender(capture);}
    @AfterEach void close() {logger.detachAppender(capture);capture.stop();}
    void complete(TorrentAccessWaitLog.Result result) {
        long start=diagnostics.started();clock.addAndGet(TimeUnit.SECONDS.toNanos(2));diagnostics.completed(start,result);
    }
    @Test void hundredsOfBriefWaitsAreSilentEvenInDebug() {
        for(int n=0;n<1000;n++) {long start=diagnostics.started();clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(25));diagnostics.completed(start,TorrentAccessWaitLog.Result.ACQUIRED);}
        assertThat(capture.list).isEmpty();
    }
    @Test void slowWaitsAreReportedAtMostOncePerMinuteWithAggregatedOutcomes() {
        complete(TorrentAccessWaitLog.Result.ACQUIRED);
        complete(TorrentAccessWaitLog.Result.TIMED_OUT);
        complete(TorrentAccessWaitLog.Result.INTERRUPTED);
        assertThat(capture.list).hasSize(1);
        clock.set(TimeUnit.SECONDS.toNanos(60));complete(TorrentAccessWaitLog.Result.ACQUIRED);
        assertThat(capture.list).hasSize(2).allSatisfy(event->assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
        assertThat(capture.list.getFirst().getFormattedMessage()).contains("duraciónMs=2000","resultado=acceso obtenido","esperasProlongadas=1").doesNotContain("Sincronización");
        assertThat(capture.list.getLast().getFormattedMessage()).contains("esperasProlongadas=3","tiemposAgotados=1","interrupciones=1");
        assertThat(clock.get()).isEqualTo(TimeUnit.SECONDS.toNanos(62));
    }
    @Test void concurrentWaitersShareTheSameLimitWithoutLosingTheirCounts() throws Exception {
        clock.set(TimeUnit.SECONDS.toNanos(2));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<24;n++) tasks.add(executor.submit(()->diagnostics.completed(0,TorrentAccessWaitLog.Result.ACQUIRED)));
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
        }
        assertThat(capture.list).hasSize(1);
        clock.set(TimeUnit.SECONDS.toNanos(60));complete(TorrentAccessWaitLog.Result.ACQUIRED);
        assertThat(capture.list).hasSize(2);
        assertThat(capture.list.getLast().getFormattedMessage()).contains("esperasProlongadas=24");
    }
    @Test void ordinaryLogLevelsDoNotAccumulateOrEmitRoutineWaits() {
        logger.setLevel(Level.INFO);complete(TorrentAccessWaitLog.Result.ACQUIRED);complete(TorrentAccessWaitLog.Result.TIMED_OUT);
        assertThat(capture.list).isEmpty();
        logger.setLevel(Level.DEBUG);complete(TorrentAccessWaitLog.Result.TIMED_OUT);
        assertThat(capture.list).singleElement().satisfies(event->assertThat(event.getFormattedMessage()).contains("resultado=tiempo agotado","esperasProlongadas=1","tiemposAgotados=1"));
    }
}
