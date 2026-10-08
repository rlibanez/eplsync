package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.exception.CatalogOperationException;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A monotonic deadline shared by every phase on the operation's thread. */
public final class CatalogOperationBudget implements AutoCloseable {
    private static final ThreadLocal<CatalogOperationBudget> CURRENT = new ThreadLocal<>();
    private final CatalogOperationBudget previous;
    private final LongSupplier clock;
    private final long deadline;
    private final Duration timeout;
    public CatalogOperationBudget(Duration timeout) { this(timeout,System::nanoTime); }
    CatalogOperationBudget(Duration timeout,LongSupplier clock) {
        if(timeout==null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1))>0)
            throw new IllegalArgumentException("El plazo debe ser positivo y no superar 24 horas");
        this.timeout=timeout; this.clock=clock; previous=CURRENT.get();
        deadline=clock.getAsLong()+timeout.toNanos(); CURRENT.set(this);
    }
    public static boolean active() { return CURRENT.get()!=null; }
    public static void check() {
        if(Thread.currentThread().isInterrupted()) throw new CatalogOperationException(HttpStatus.SERVICE_UNAVAILABLE,
            "El procesamiento del catálogo fue interrumpido; los cambios no se han confirmado");
        var budget=CURRENT.get();
        if(budget!=null) budget.checkOwn();
    }
    private void checkOwn() {
        if(clock.getAsLong()-deadline>=0)
            throw new CatalogOperationException(HttpStatus.REQUEST_TIMEOUT,
                "La operación de catálogo ha superado el tiempo máximo de "+displayTimeout()+"; los cambios no se han confirmado");
    }
    private String displayTimeout() {
        long seconds=Math.max(1,timeout.toSeconds());
        return seconds>=60 && seconds%60==0 ? seconds/60+" minutos" : seconds+" segundos";
    }
    public static Duration remaining(Duration phaseLimit) {
        check(); var budget=CURRENT.get();
        return budget==null ? phaseLimit : Duration.ofNanos(Math.min(phaseLimit.toNanos(),Math.max(1,budget.deadline-budget.clock.getAsLong())));
    }
    public static int transactionSeconds() {
        return CURRENT.get()==null ? 1800 : (int)Math.max(1,(remaining(Duration.ofDays(1)).toNanos()+999_999_999L)/1_000_000_000L);
    }
    /** Check again before the outer transaction can commit; expiry must roll back writes. */
    public static void enlist() {
        check(); var budget=CURRENT.get();
        if(budget==null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        if(TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(s -> s instanceof CommitGuard guard && guard.budget==budget)) return;
        TransactionSynchronizationManager.registerSynchronization(new CommitGuard(budget));
    }
    private record CommitGuard(CatalogOperationBudget budget) implements TransactionSynchronization {
        @Override public void beforeCommit(boolean readOnly) { budget.checkOwn(); if(Thread.currentThread().isInterrupted()) check(); }
    }
    @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
}
