package com.rlibanez.eplsync.torrent.downloads;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/** Diagnostics only: never acquires the torrent guard or changes retry decisions. */
final class TorrentAccessWaitLog {
    enum Result { ACQUIRED, TIMED_OUT, INTERRUPTED }
    private static final long SLOW_WAIT=TimeUnit.SECONDS.toNanos(1),REPORT_INTERVAL=TimeUnit.MINUTES.toNanos(1);
    private final Logger logger;
    private final LongSupplier clock;
    private boolean reported;
    private long lastReport,waits,timeouts,interruptions;
    TorrentAccessWaitLog(Logger logger,LongSupplier clock) {this.logger=logger;this.clock=clock;}
    long started() {return clock.getAsLong();}
    synchronized void completed(long started,Result result) {
        if(!logger.isDebugEnabled()) return;
        long now=clock.getAsLong(),duration=now-started;
        if(duration<SLOW_WAIT) return;
        waits++;if(result==Result.TIMED_OUT) timeouts++;if(result==Result.INTERRUPTED) interruptions++;
        if(reported && now-lastReport<REPORT_INTERVAL) return;
        logger.debug("Espera prolongada para acceder al cliente torrent: duraciónMs={}, resultado={}, esperasProlongadas={}, tiemposAgotados={}, interrupciones={}",
            TimeUnit.NANOSECONDS.toMillis(duration),switch(result) {case ACQUIRED->"acceso obtenido";case TIMED_OUT->"tiempo agotado";case INTERRUPTED->"interrumpida";},waits,timeouts,interruptions);
        reported=true;lastReport=now;waits=0;timeouts=0;interruptions=0;
    }
}
