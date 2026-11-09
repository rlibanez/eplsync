package com.rlibanez.eplsync.torrent;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
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
        private static final long WINDOW = TimeUnit.MINUTES.toNanos(1);
        private static final int WARN_AFTER = 3;

        private static final class Sample {
            long count, nanos, max, errors, slow;

            boolean degraded() {
                long half = count / 2 + count % 2;
                return errors >= half || slow >= half;
            }
        }

        private final Map<String, Integer> degradedWindows = new HashMap<>();
        private final Map<String,Sample> samples=new TreeMap<>();
        private final Logger logger;
        private final LongSupplier clock;
        private long last;
        Recorder(Logger logger,LongSupplier clock) {this.logger=logger;this.clock=clock;last=clock.getAsLong();}
        synchronized void record(String phase,long nanos,boolean failed) {
            if (!logger.isDebugEnabled() && !logger.isWarnEnabled()) return;
            long now = clock.getAsLong();
            if (now - last >= 3 * WINDOW) {
                samples.clear();
                degradedWindows.clear();
                last = now;
            }

            var sample = samples.computeIfAbsent(phase, key -> new Sample());
            sample.count++;
            sample.nanos += Math.max(0, nanos);
            sample.max = Math.max(sample.max, nanos);
            if (failed) sample.errors++;
            long slowThreshold = TimeUnit.SECONDS.toNanos("indice_completo".equals(phase) ? 20 : 5);
            if (nanos >= slowThreshold) sample.slow++;
            if (now - last < WINDOW) return;

            degradedWindows.keySet().retainAll(samples.keySet());
            var summary = new StringJoiner("; ");
            samples.forEach((name, value) -> {
                String metrics = name + "[operaciones=" + value.count
                    + ", mediaMs=" + TimeUnit.NANOSECONDS.toMillis(value.nanos / value.count)
                    + ", máximoMs=" + TimeUnit.NANOSECONDS.toMillis(value.max)
                    + ", errores=" + value.errors + "]";
                summary.add(metrics);
                if (!value.degraded()) {
                    degradedWindows.remove(name);
                    return;
                }
                int consecutive = degradedWindows.compute(name,
                    (key, previous) -> previous == null ? 1 : Math.min(previous + 1, WARN_AFTER + 1));
                if (consecutive == WARN_AFTER) {
                    logger.warn("Fallos o lentitud sostenida en operaciones torrent: ventanas={}, {}, operacionesLentas={}",
                        consecutive, metrics, value.slow);
                }
            });
            logger.debug("Rendimiento de operaciones torrent: intervaloSegundos={}, {}",
                TimeUnit.NANOSECONDS.toSeconds(now - last), summary);
            samples.clear();
            last = now;
        }
    }
}
