package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CoverCheckProperties;
import com.rlibanez.eplsync.maintenance.MaintenanceGate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/** One background task per process; results survive browser navigation, not server restarts. */
@Service
public class CoverTaskService {
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    public record Options(long connectTimeoutMs, long requestTimeoutMs, long batchTimeoutMs, int concurrency) {
        public static Options from(CoverCheckProperties p) {
            return new Options(p.getConnectTimeout().toMillis(), p.getRequestTimeout().toMillis(),
                    p.getBatchTimeout().toMillis(), p.getConcurrency());
        }
        public CoverCheckProperties properties() {
            var p = new CoverCheckProperties();
            p.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
            p.setRequestTimeout(Duration.ofMillis(requestTimeoutMs));
            p.setBatchTimeout(Duration.ofMillis(batchTimeoutMs));
            p.setConcurrency(concurrency);
            if (!p.isTimeoutConfigurationValid() || concurrency < 1 || concurrency > 32)
                throw new IllegalArgumentException("Los tiempos deben estar entre 1ms y 5min: connect <= request <= batch; concurrency entre 1 y 32");
            return p;
        }
    }
    public record Status(String id, String state, boolean dryRun, boolean onlyUnchecked, Options options, long checked, long total,
                         CoverCheckService.Report summary, String error) {}
    private static class Task {
        final com.rlibanez.eplsync.events.EventContext.Origin origin = com.rlibanez.eplsync.events.EventContext.origin();
        final String id = UUID.randomUUID().toString();
        final boolean dryRun;
        final boolean onlyUnchecked;
        final Options options;
        volatile String state = "RUNNING";
        volatile long checked;
        volatile long total;
        volatile CoverCheckService.Report summary;
        volatile String error;
        Task(boolean dryRun, boolean onlyUnchecked, Options options) { this.dryRun = dryRun; this.onlyUnchecked = onlyUnchecked; this.options = options; }
        Status status() { return new Status(id, state, dryRun, onlyUnchecked, options, checked, total, summary, error); }
    }
    private final CoverCheckService checks;
    private final CoverProbeFactory probes;
    private final CoverCheckProperties defaults;
    private final MaintenanceGate gate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Task current;

    public CoverTaskService(CoverCheckService checks, CoverProbeFactory probes, CoverCheckProperties defaults, MaintenanceGate gate) {
        this.checks = checks; this.probes = probes; this.defaults = defaults; this.gate = gate;
    }
    public Options defaults() { return Options.from(defaults); }
    public Status current() { var task = current; return task == null ? null : task.status(); }

    public synchronized Status start(boolean dryRun, boolean onlyUnchecked, Options options) {
        var effective = options == null ? defaults() : options;
        var properties = effective.properties();
        if ((current != null && current.state.equals("RUNNING")) || checks.isBusy()) throw new CoverCheckService.BusyException();
        var task = new Task(dryRun, onlyUnchecked, effective);
        current = task;
        executor.submit(() -> com.rlibanez.eplsync.events.EventContext.withOrigin(task.origin, () -> { run(task, properties); return null; }));
        return task.status();
    }

    private void run(Task task, CoverCheckProperties properties) {
        if (!gate.enter(false)) {
            task.error = "MAINTENANCE_BUSY"; task.state = "FAILED";
            if (events != null) events.record(com.rlibanez.eplsync.events.EventJournal.Category.COVERS, "CHECK",
                com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED, task.origin, task.id, java.util.Map.of("reason", "MAINTENANCE_BUSY"));
            return;
        }
        CoverProbe probe = null;
        boolean checking = false;
        try {
            probe = probes.create(properties);
            checking = true;
            var report = checks.check(task.dryRun, 0, null, null, task.onlyUnchecked, properties, probe,
                    (done, total) -> { task.total = total; task.checked = done; });
            task.summary = new CoverCheckService.Report(report.dryRun(), report.checked(), report.available(), report.unavailable(),
                    report.inconclusive(), report.wouldChange(), report.updated(), null, false, List.of());
            task.state = "COMPLETED";
        } catch (CoverCheckService.BusyException ex) {
            task.error = "CHECK_BUSY"; task.state = "FAILED";
        } catch (Exception ex) {
            if (!checking && events != null) events.record(com.rlibanez.eplsync.events.EventJournal.Category.COVERS, "CHECK",
                com.rlibanez.eplsync.events.EventJournal.Outcome.FAILED, task.origin, task.id,
                java.util.Map.of("reason", ex.getClass().getSimpleName()));
            task.error = "CHECK_FAILED"; task.state = "FAILED";
        } finally {
            if (probe != null) probe.shutdown();
            gate.leave(false);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() { executor.shutdownNow(); }
}
