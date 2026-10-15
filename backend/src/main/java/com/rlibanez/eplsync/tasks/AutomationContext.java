package com.rlibanez.eplsync.tasks;

/** Marks job creation before its durable automation link is committed. */
public final class AutomationContext {
    private AutomationContext() {}
    private static final ThreadLocal<Boolean> ACTIVE=new ThreadLocal<>();
    public static boolean active() {return Boolean.TRUE.equals(ACTIVE.get());}
    public static <T> T run(java.util.function.Supplier<T> work) {
        var previous=ACTIVE.get();ACTIVE.set(true);
        try {return work.get();} finally {if(previous==null) ACTIVE.remove();else ACTIVE.set(previous);}
    }
}
