package com.rlibanez.eplsync.qbittorrent;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/** Bounded diagnostics per client configuration; never logs messages, URLs or credentials. */
final class QBittorrentCommunicationLog {
    private final Logger logger;
    private final LongSupplier clock;
    private boolean reported;
    private long lastReport,failures;
    QBittorrentCommunicationLog(Logger logger,LongSupplier clock) {this.logger=logger;this.clock=clock;}
    synchronized void failed(String method,String endpoint,Throwable cause) {
        if(!logger.isDebugEnabled()) return;
        failures++;long now=clock.getAsLong();
        if(reported && now-lastReport<TimeUnit.MINUTES.toNanos(1)) return;
        logger.debug("Fallo de comunicación qBittorrent {} {}: {}; fallosDesdeUltimoAviso={}",method,endpoint,technicalCause(cause),failures);
        reported=true;lastReport=now;failures=0;
    }
    private static String technicalCause(Throwable cause) {
        var names=new java.util.ArrayList<String>();var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(var item=cause;item!=null && seen.add(item) && names.size()<5;item=item.getCause()) names.add(item.getClass().getSimpleName());
        return String.join(" <- ",names);
    }
}
