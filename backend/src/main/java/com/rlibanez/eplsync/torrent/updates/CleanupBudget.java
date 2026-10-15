package com.rlibanez.eplsync.torrent.updates;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One deadline for the whole cycle, including interruptible HTTP calls. */
final class CleanupBudget {
    private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("cleanup-deadline-",0).factory());
    private static final ThreadLocal<CleanupBudget> CURRENT=new ThreadLocal<>();
    static {TIMER.setRemoveOnCancelPolicy(true);}
    private final Thread thread=Thread.currentThread();
    private final long deadline;
    private boolean finished,expired;
    private CleanupBudget(Duration duration) {deadline=System.nanoTime()+duration.toNanos();}
    private synchronized void expire() {if(!finished) {expired=true;thread.interrupt();}}
    static boolean available() {
        var budget=CURRENT.get();
        return !Thread.currentThread().isInterrupted() && (budget==null || System.nanoTime()<budget.deadline);
    }
    static <T> T run(Supplier<T> action) {return run(Duration.ofSeconds(30),action);}
    static <T> T run(Duration duration,Supplier<T> action) {
        if(CURRENT.get()!=null) return action.get();
        var budget=new CleanupBudget(duration);
        boolean interrupted=Thread.currentThread().isInterrupted();
        CURRENT.set(budget);
        var alarm=TIMER.schedule(budget::expire,duration.toNanos(),TimeUnit.NANOSECONDS);
        try {return action.get();}
        finally {
            synchronized(budget) {budget.finished=true;alarm.cancel(false);if(budget.expired && !interrupted) Thread.interrupted();}
            CURRENT.remove();
        }
    }
}
