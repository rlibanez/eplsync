package com.rlibanez.eplsync.events;

import java.util.function.Supplier;

/** Future schedulers explicitly set SCHEDULED; HTTP/manual operations default to MANUAL. */
public final class EventContext {
    public enum Origin { MANUAL, SCHEDULED, SYSTEM }
    private static final ThreadLocal<Origin> origin = ThreadLocal.withInitial(() -> Origin.MANUAL);
    public static Origin origin() { return origin.get(); }
    public static <T> T withOrigin(Origin value, Supplier<T> action) {
        var previous = origin.get();
        origin.set(value);
        try { return action.get(); } finally { origin.set(previous); }
    }
    private EventContext() {}
}
