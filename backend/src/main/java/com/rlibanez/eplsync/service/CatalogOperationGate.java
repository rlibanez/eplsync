package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CatalogImportProperties;
import com.rlibanez.eplsync.exception.CatalogOperationException;
import com.rlibanez.eplsync.importer.CatalogOperationBudget;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** No waiting queue. Nested calls keep the original deadline and reservation. */
@Component
public class CatalogOperationGate {
    private final ReentrantLock lock=new ReentrantLock();
    private final CatalogImportProperties properties;
    public CatalogOperationGate(CatalogImportProperties properties) { this.properties=properties; }
    public Lease acquire() {
        if(!lock.tryLock()) throw new CatalogOperationException(HttpStatus.CONFLICT,
            "Hay otra operación de catálogo en curso; inténtalo cuando termine");
        CatalogOperationBudget budget=null;
        try {
            budget=lock.getHoldCount()==1 && !CatalogOperationBudget.active() ? new CatalogOperationBudget(properties.getOperationTimeout()) : null;
            CatalogOperationBudget.check();
            return new Lease(budget);
        } catch(RuntimeException ex) { if(budget!=null) budget.close(); lock.unlock(); throw ex; }
    }
    public <T> T run(Supplier<T> work) { try(var lease=acquire()) { return work.get(); } }
    public final class Lease implements AutoCloseable {
        private final CatalogOperationBudget budget;
        private Lease(CatalogOperationBudget budget) { this.budget=budget; }
        @Override public void close() { try { if(budget!=null) budget.close(); } finally { lock.unlock(); } }
    }
}
