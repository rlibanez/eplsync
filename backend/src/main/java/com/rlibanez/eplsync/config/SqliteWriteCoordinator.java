package com.rlibanez.eplsync.config;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.transaction.CannotCreateTransactionException;

/** One fair writer queue, entered before borrowing a connection. Reads never enter it. */
public final class SqliteWriteCoordinator {
    private final ReentrantLock lock=new ReentrantLock(true);
    void acquire(int seconds) {
        try {
            if(!lock.tryLock(seconds,TimeUnit.SECONDS)) throw new com.rlibanez.eplsync.ordering.SqliteContention.WriterBusy();
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CannotCreateTransactionException("Escritura SQLite interrumpida",ex);
        }
    }
    void release() {lock.unlock();}
    public <T> T write(Supplier<T> action) {
        acquire(30);
        try {return action.get();}finally {release();}
    }
}
