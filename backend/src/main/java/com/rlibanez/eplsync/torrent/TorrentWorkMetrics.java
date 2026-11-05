package com.rlibanez.eplsync.torrent;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded phase timings, without request bodies, hashes, destinations or credentials. No idle polling. */
public final class TorrentWorkMetrics {
    private TorrentWorkMetrics() {}
    private static final Recorder shared=new Recorder(LoggerFactory.getLogger(TorrentWorkMetrics.class),System::nanoTime);
    public static void record(String phase,long nanos,boolean failed) {shared.record(phase,nanos,failed);}
    static final class Recorder {
        private static final class Sample {long count,nanos,max,errors;}
        private final Map<String,Sample> samples=new TreeMap<>();
        private final Logger logger;
        private final LongSupplier clock;
        private long last;
        Recorder(Logger logger,LongSupplier clock) {this.logger=logger;this.clock=clock;last=clock.getAsLong();}
        synchronized void record(String phase,long nanos,boolean failed) {
            if(!logger.isInfoEnabled()) return;
            var sample=samples.computeIfAbsent(phase,key->new Sample());sample.count++;sample.nanos+=Math.max(0,nanos);
            sample.max=Math.max(sample.max,nanos);if(failed) sample.errors++;
            long now=clock.getAsLong();if(now-last<TimeUnit.MINUTES.toNanos(1)) return;
            var summary=new StringJoiner("; ");
            samples.forEach((name,value)->summary.add(name+"[operaciones="+value.count+", mediaMs="+TimeUnit.NANOSECONDS.toMillis(value.nanos/value.count)+", máximoMs="+TimeUnit.NANOSECONDS.toMillis(value.max)+", errores="+value.errors+"]"));
            logger.info("Rendimiento de operaciones torrent: intervaloSegundos={}, {}",TimeUnit.NANOSECONDS.toSeconds(now-last),summary);
            samples.clear();last=now;
        }
    }
}
