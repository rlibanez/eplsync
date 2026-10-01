package com.rlibanez.eplsync.maintenance;

import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import com.rlibanez.eplsync.torrent.bulk.BulkWorker;
import com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import jakarta.persistence.EntityManager;
import com.rlibanez.eplsync.service.CatalogImportService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatabaseResetService {
    private final CatalogImportService catalog;
    private final BulkStore bulk;
    private final ObjectProvider<BulkWorker> workers;
    private final DownloadTrackingService tracking;
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DatabaseResetService.class);

    public record ResetResult(boolean success, int catalogBooks, int downloads,
            int jobs, int jobItems, int updatePlans, int cleanupRecords, int recordsImported, com.rlibanez.eplsync.model.CatalogMetadata metadata) {}

    public DatabaseResetService(BulkStore bulk, ObjectProvider<BulkWorker> workers,
            DownloadTrackingService tracking, EntityManager em, PlatformTransactionManager manager, CatalogImportService catalog) {
        this.catalog = catalog;
        this.bulk = bulk; this.workers = workers; this.tracking = tracking; this.em = em;
        this.transactions = new TransactionTemplate(manager);
    }

    public ResetResult reset() {
        // Worker dispatch and job creation use this same monitor. Do not hold a
        // database transaction while waiting for the worker or tracking locks.
        synchronized (bulk) {
            BulkWorker worker = workers.getIfAvailable();
            if (worker != null && worker.hasInFlightSends()) throw busy();
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
                    em.clear();
                    var imported = catalog.importCatalog();
                    if (!imported.success() || imported.errors() > 0 || imported.recordsCreated() == 0)
                        throw new IllegalStateException("El catálogo está vacío o contiene errores; se conserva la base de datos anterior");
                    return new ResetResult(true, books, downloads, jobs, items, plans, cleanup, imported.recordsCreated(), imported.metadata());
                });
                // Only discard the in-memory queue after the transaction commits.
                if (worker != null) worker.clearIdleState();
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
