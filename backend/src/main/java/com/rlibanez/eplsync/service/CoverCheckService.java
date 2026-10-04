package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.repository.CatalogBookRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CoverCheckService {
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    private java.util.Map<String, ?> eventSummary(Report report) {
        return java.util.Map.of("dryRun", report.dryRun(), "checked", report.checked(), "available", report.available(),
                "unavailable", report.unavailable(), "inconclusive", report.inconclusive(), "updated", report.updated());
    }
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CoverCheckService.class);
    public record Item(long eplId, String coverUrl, Boolean previousAvailable, Boolean available,
                       Integer httpStatus, String reason, boolean wouldChange, boolean updated) {}
    public record Report(boolean dryRun, int checked, int available, int unavailable, int inconclusive,
                         int wouldChange, int updated, Long nextAfterId, boolean hasMore, List<Item> items) {
        /** Filter displayed results, keeping batch totals and the scan cursor unchanged. */
        public Report filterAvailability(Boolean coverAvailable) {
            if (coverAvailable == null) return this;
            return new Report(dryRun, checked, available, unavailable, inconclusive, wouldChange, updated,
                    nextAfterId, hasMore, items.stream()
                    .filter(item -> coverAvailable.equals(item.available())).toList());
        }
    }
    private final CatalogBookRepository repository;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private CoverProbeFactory probeFactory;
    private final CoverProbe probe;
    private final TransactionTemplate transactions;
    private final ReentrantLock lock = new ReentrantLock();
    private final com.rlibanez.eplsync.config.CoverCheckProperties properties;

    public CoverCheckService(CatalogBookRepository repository, CoverProbe probe, TransactionTemplate transactions,
            com.rlibanez.eplsync.config.CoverCheckProperties properties) {
        this.repository = repository;
        this.probe = probe;
        this.transactions = transactions;
        this.properties = properties;
    }

    public Report check(boolean dryRun, long afterId, Long eplId, Integer size, boolean onlyUnchecked) {
        var effective = properties.effective();
        if (effective == properties) return check(dryRun, afterId, eplId, size, onlyUnchecked, properties, probe, (done, total) -> {});
        var runProbe = probeFactory == null ? new CoverProbe(effective) : probeFactory.create(effective);
        try { return check(dryRun, afterId, eplId, size, onlyUnchecked, effective, runProbe, (done, total) -> {}); }
        finally { runProbe.shutdown(); }
    }

    public boolean isBusy() { return lock.isLocked(); }

    public Report check(boolean dryRun, long afterId, Long eplId, Integer size, boolean onlyUnchecked,
            com.rlibanez.eplsync.config.CoverCheckProperties options, CoverProbe runProbe,
            java.util.function.BiConsumer<Long, Long> listener) {
        if (events == null || eplId != null)
            return performCheck(dryRun, afterId, eplId, size, onlyUnchecked, options, runProbe, listener);
        return events.run(com.rlibanez.eplsync.events.EventJournal.Category.COVERS, "CHECK",
            () -> performCheck(dryRun, afterId, eplId, size, onlyUnchecked, options, runProbe, listener), this::eventSummary);
    }
    private Report performCheck(boolean dryRun, long afterId, Long eplId, Integer size, boolean onlyUnchecked,
            com.rlibanez.eplsync.config.CoverCheckProperties options, CoverProbe runProbe,
            java.util.function.BiConsumer<Long, Long> listener) {
        if (afterId < 0 || (eplId != null && eplId < 1) || (size != null && size < 1)) {
            throw new IllegalArgumentException("afterId >= 0, eplId >= 1, size >= 1 (opcional)");
        }
        if (!options.isTimeoutConfigurationValid() || options.getConcurrency() < 1 || options.getConcurrency() > 32)
            throw new IllegalArgumentException("Parámetros de comprobación inválidos");
        if (!lock.tryLock()) throw new BusyException();
        int concurrency = options.getConcurrency();
        var batchTimeout = options.getBatchTimeout();
        var workers = Executors.newFixedThreadPool(concurrency);
        long started = System.nanoTime();
        var progress = new Progress(listener);
        try {
            long candidates = repository.countCovers(afterId, eplId, onlyUnchecked);
            progress.total = size == null ? candidates : Math.min(candidates, size);
            listener.accept(0L, progress.total);
            log.info("Inicio comprobación de portadas: dryRun={}, total={}, onlyUnchecked={}, eplId={}, afterId={}, size={}, "
                            + "concurrency={}, connectTimeout={}, requestTimeout={}, batchTimeout={}",
                    dryRun, progress.total, onlyUnchecked, eplId, afterId, size == null ? "sin límite" : size,
                    concurrency, options.getConnectTimeout(), options.getRequestTimeout(), batchTimeout);
            var results = new LinkedHashMap<String, CoverProbe.Result>();
            List<Item> checked = new ArrayList<>();
            long cursor = afterId;
            boolean more;
            do {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                // Internal DB batches bound reads; an omitted size never limits the scan.
                int batchSize = size == null ? 50 : Math.min(50, size - checked.size());
                var selected = repository.findCovers(cursor, eplId, onlyUnchecked, PageRequest.of(0, batchSize + 1));
                more = selected.size() > batchSize;
                var books = selected.subList(0, Math.min(batchSize, selected.size()));
                for (var book : books) {
                    if (results.containsKey(book.getCoverUrl())) progress.advance();
                }
                probeUrls(books.stream().map(CatalogBookRepository.CoverIdentity::getCoverUrl)
                        .distinct().filter(url -> !results.containsKey(url)).toList(), results, url -> {
                            for (var book : books) {
                                if (book.getCoverUrl().equals(url)) progress.advance();
                            }
                        }, workers, runProbe, concurrency, batchTimeout);
                for (var book : books) {
                    var result = results.get(book.getCoverUrl());
                    boolean change = result.available() != null && !Objects.equals(result.available(), book.getCoverAvailable());
                    checked.add(new Item(book.getEplId(), book.getCoverUrl(), book.getCoverAvailable(), result.available(),
                            result.httpStatus(), result.reason(), change, false));
                }
                if (!books.isEmpty()) cursor = books.getLast().getEplId();
            } while (more && (size == null || checked.size() < size));

            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            // All network checks finish before any POST writes. GET never enters this transaction.
            List<Item> items = dryRun ? checked : transactions.execute(status -> {
                List<Item> applied = new ArrayList<>();
                for (var item : checked) {
                    boolean updated = item.wouldChange() && repository.updateCoverAvailability(item.eplId(), item.coverUrl(),
                            item.previousAvailable(), item.available()) == 1;
                    applied.add(new Item(item.eplId(), item.coverUrl(), item.previousAvailable(), item.available(),
                            item.httpStatus(), item.wouldChange() && !updated ? "CONCURRENT_CHANGE" : item.reason(),
                            item.wouldChange(), updated));
                }
                if (events != null && eplId == null) events.completed(com.rlibanez.eplsync.events.EventJournal.Category.COVERS, "CHECK",
                    java.util.Map.of("dryRun", false, "checked", applied.size(), "available", count(applied, true),
                        "unavailable", count(applied, false), "inconclusive", applied.stream().filter(i -> i.available() == null).count(),
                        "updated", applied.stream().filter(Item::updated).count()));
                return applied;
            });
            var report = new Report(dryRun, items.size(), count(items, true), count(items, false),
                    (int) items.stream().filter(i -> i.available() == null).count(),
                    (int) items.stream().filter(Item::wouldChange).count(), (int) items.stream().filter(Item::updated).count(),
                    more ? cursor : null, more, List.copyOf(items));
            log.info("Fin comprobación de portadas: dryRun={}, comprobados={}, disponibles={}, noEncontrados={}, "
                            + "inconcluyentes={}, cambiosPropuestos={}, actualizados={}, urlsConsultadas={}, duraciónMs={}",
                    dryRun, report.checked(), report.available(), report.unavailable(), report.inconclusive(),
                    report.wouldChange(), report.updated(), results.size(), elapsed(started));
            return report;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Comprobación de portadas interrumpida: dryRun={}, comprobados={}/{}, duraciónMs={}; no se han guardado resultados",
                    dryRun, progress.processed, progress.total, elapsed(started));
            throw new IllegalStateException("Comprobación interrumpida; no se han guardado resultados", ex);
        } catch (RuntimeException ex) {
            log.error("Comprobación de portadas fallida: dryRun={}, comprobados={}/{}, duraciónMs={}",
                    dryRun, progress.processed, progress.total, elapsed(started), ex);
            throw ex;
        } finally {
            workers.shutdownNow();
            lock.unlock();
        }
    }

    private void probeUrls(List<String> urls, java.util.Map<String, CoverProbe.Result> results,
            java.util.function.Consumer<String> onChecked, ExecutorService workers, CoverProbe runProbe,
            int concurrency, java.time.Duration batchTimeout) throws InterruptedException {
        // Keep the number of queued requests bounded, even during a full scan.
        for (int offset = 0; offset < urls.size(); offset += concurrency) {
            var wave = urls.subList(offset, Math.min(offset + concurrency, urls.size()));
            List<Callable<CoverProbe.Result>> tasks = wave.stream()
                    .<Callable<CoverProbe.Result>>map(url -> () -> runProbe.check(url)).toList();
            var futures = workers.invokeAll(tasks, batchTimeout.toNanos(), TimeUnit.NANOSECONDS);
            for (int i = 0; i < wave.size(); i++) {
                CoverProbe.Result result;
                try {
                    result = futures.get(i).get();
                } catch (CancellationException | ExecutionException ex) {
                    result = new CoverProbe.Result(null, null, "TIMEOUT_OR_NETWORK_ERROR");
                }
                results.put(wave.get(i), result);
                onChecked.accept(wave.get(i));
            }
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private static class Progress {
        long total;
        long processed;
        int nextPercent = 10;
        private final java.util.function.BiConsumer<Long, Long> listener;
        Progress(java.util.function.BiConsumer<Long, Long> listener) { this.listener = listener; }

        void advance() {
            processed++;
            listener.accept(processed, total);
            if (total == 0) return;
            int percent = (int) Math.min(100, processed * 100 / total);
            if (percent >= nextPercent) {
                log.info("Progreso comprobación de portadas: {}% ({}/{} libros)", percent, processed, total);
                nextPercent = (percent / 10 + 1) * 10;
            }
        }
    }

    private int count(List<Item> items, boolean value) {
        return (int) items.stream().filter(i -> Boolean.valueOf(value).equals(i.available())).count();
    }

    public static class BusyException extends RuntimeException {}

}
