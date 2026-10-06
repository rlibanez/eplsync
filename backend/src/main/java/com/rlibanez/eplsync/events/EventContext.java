package com.rlibanez.eplsync.events;

import java.util.function.Supplier;

/** Future schedulers explicitly set SCHEDULED; HTTP/manual operations default to MANUAL. */
public final class EventContext {
    public enum Origin { MANUAL, SCHEDULED, SYSTEM }
    public record Actor(String id, String username, String kind) {
        public static Actor system() { return new Actor(null, null, "SYSTEM"); }
        public static Actor unknown() { return new Actor(null, null, "UNKNOWN"); }
    }
    private static final ThreadLocal<Actor> actor = new ThreadLocal<>();
    public static Actor actor() {
        if (actor.get() != null) return actor.get();
        if (origin() != Origin.MANUAL) return Actor.system();
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            if (auth.getPrincipal() instanceof com.rlibanez.eplsync.security.Account account)
                return new Actor(account.id(), account.username(), "USER");
            return new Actor(null, auth.getName(), "USER");
        }
        return Actor.system();
    }
    public static Actor actorFor(Origin value) {
        return actor.get() != null ? actor.get() : value == Origin.MANUAL ? actor() : Actor.system();
    }
    public static <T> T withActor(Actor value, Supplier<T> action) {
        var previous = actor.get(); actor.set(value);
        try { return action.get(); } finally { if (previous == null) actor.remove(); else actor.set(previous); }
    }
    private static final ThreadLocal<Origin> origin = ThreadLocal.withInitial(() -> Origin.MANUAL);
    public static Origin origin() { return origin.get(); }
    public static <T> T withOrigin(Origin value, Supplier<T> action) {
        var previous = origin.get();
        origin.set(value);
        try { return action.get(); } finally { origin.set(previous); }
    }
    private EventContext() {}
}
