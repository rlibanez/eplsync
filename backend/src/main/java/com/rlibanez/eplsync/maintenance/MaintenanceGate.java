package com.rlibanez.eplsync.maintenance;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

/** Coordinates whole HTTP requests, including their pre-transaction work. */
@Component
public class MaintenanceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    public boolean enter(boolean reset) {
        return reset ? lock.writeLock().tryLock() : lock.readLock().tryLock();
    }
    public void leave(boolean reset) {
        if (reset) lock.writeLock().unlock(); else lock.readLock().unlock();
    }
}
