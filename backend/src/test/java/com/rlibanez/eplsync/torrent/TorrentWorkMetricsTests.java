package com.rlibanez.eplsync.torrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TorrentWorkMetricsTests {
    @Test void timingsAreAggregatedAndReportedAtMostOnceAMinuteWithoutIdleWork() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("torrent-metrics-test");
        var previous=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.INFO);
        var output=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();output.start();logger.addAppender(output);
        try {
            var clock=new AtomicLong();var metrics=new TorrentWorkMetrics.Recorder(logger,clock::get);
            for(int i=0;i<1000;i++) metrics.record("http_envio",TimeUnit.MILLISECONDS.toNanos(10),false);
            assertThat(output.list).isEmpty();clock.set(TimeUnit.MINUTES.toNanos(1));
            metrics.record("consulta_hashes",TimeUnit.MILLISECONDS.toNanos(80),true);
            assertThat(output.list).singleElement().satisfies(event->assertThat(event.getFormattedMessage())
                .contains("http_envio[operaciones=1000, mediaMs=10, máximoMs=10, errores=0]","consulta_hashes[operaciones=1, mediaMs=80, máximoMs=80, errores=1]"));
            clock.set(TimeUnit.MINUTES.toNanos(10));assertThat(output.list).hasSize(1);
        } finally {logger.detachAppender(output);logger.setLevel(previous);}
    }
}
