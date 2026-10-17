package com.rlibanez.eplsync.ordering;

/** Only SQLite lock contention is deferred; integrity, I/O and programming errors stay visible. */
public final class SqliteContention {
    private SqliteContention() {}
    public static final class WriterBusy extends org.springframework.transaction.CannotCreateTransactionException {
        private static final long serialVersionUID=1L;
        public WriterBusy() {super("Tiempo de espera agotado para escribir en SQLite");}
    }
    public static boolean isBusy(Throwable error) {
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(var cause=error;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof WriterBusy) return true;
            if(cause instanceof java.sql.SQLException sql && ((sql.getErrorCode() & 255)==5 || (sql.getErrorCode() & 255)==6)) return true;
        }
        return false;
    }
}
