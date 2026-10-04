package com.rlibanez.eplsync.maintenance;

import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import com.rlibanez.eplsync.torrent.bulk.BulkWorker;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatabaseResetService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.rlibanez.eplsync.service.CatalogSuggestionService suggestions;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.service.CoverTaskService coverTasks;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.service.CatalogMissingService missing;
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.service.CatalogImportStore previews;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.rlibanez.eplsync.settings.ServerSettings settings;
    private final BulkStore bulk;
    private final ObjectProvider<BulkWorker> workers;
    private final DownloadTrackingService tracking;
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DatabaseResetService.class);

    public record ResetResult(boolean success, int catalogBooks, int downloads,
            int jobs, int jobItems, int updatePlans, int cleanupRecords, int metadataRecords, int events, int settingsRecords) {}

    public DatabaseResetService(BulkStore bulk, ObjectProvider<BulkWorker> workers,
            DownloadTrackingService tracking, EntityManager em, PlatformTransactionManager manager) {

        this.bulk = bulk; this.workers = workers; this.tracking = tracking; this.em = em;
        this.transactions = new TransactionTemplate(manager);
    }

    public ResetResult reset() {
        return performReset();
    }
    private ResetResult performReset() {
        // Worker dispatch and job creation use this same monitor. Do not hold a
        // database transaction while waiting for the worker or tracking locks.
        synchronized (bulk) {
            BulkWorker worker = workers.getIfAvailable();
            if (worker != null && worker.hasInFlightSends()) throw busy();
            if (coverTasks != null && coverTasks.current() != null && coverTasks.current().state().equals("RUNNING")) throw busy();
            return tracking.exclusive(() -> {
                ResetResult result = transactions.execute(status -> {
                    // Also protect installations with the worker disabled after an interrupted send.
                    if (em.createQuery("select count(i) from BulkItem i where i.state = "
                            + "com.rlibanez.eplsync.torrent.bulk.BulkItem.State.IN_FLIGHT", Long.class)
                            .getSingleResult() > 0) throw busy();
                    int cleanup = delete("UpdateCleanup");
                    int plans = delete("UpdatePlan");
                    int items = delete("BulkItem");
                    int jobs = delete("BulkJob");
                    int downloads = delete("DownloadRecord");
                    int books = delete("CatalogBook");
                    if (suggestions != null) suggestions.invalidateAfterCommit();
                    int metadata = delete("CatalogMetadata");
                    int settingsCount = delete("StoredSetting");
                    int eventCount = events == null ? em.createNativeQuery("delete from app_events").executeUpdate() : events.clearForReset();
                    em.clear();
                    return new ResetResult(true, books, downloads, jobs, items, plans, cleanup, metadata, eventCount, settingsCount);
                });
                // Only discard the in-memory queue after the transaction commits.
                if (settings != null) settings.resetAfterCommit();
                if (worker != null) worker.clearIdleState();
                if (coverTasks != null) coverTasks.clearIdleState();
                if (missing != null) missing.clear();
                if (previews != null) previews.clear();
                log.warn("Base de datos reiniciada: {}", result);
                return result;
            });
        }
    }

    private int delete(String entity) {
        return em.createQuery("delete from " + entity).executeUpdate();
    }
    private static TorrentOperationException busy() {
        return new TorrentOperationException(HttpStatus.CONFLICT,
                "Hay envíos en curso; pausa o cancela los trabajos y espera a que terminen los envíos antes de reiniciar");
    }
}
