package com.rlibanez.eplsync.config;

import java.sql.SQLException;
import java.util.Locale;
import com.zaxxer.hikari.SQLExceptionOverride;

/** A JDBC/native transaction mismatch is not ordinary lock contention. Discard the
 * physical connection rather than lending its inconsistent state to another job. */
public final class SqliteConnectionExceptionOverride implements SQLExceptionOverride {
    @java.lang.Override public SQLExceptionOverride.Override adjudicate(SQLException error) {
        if ((error.getErrorCode() & 255)==1 && error.getMessage()!=null) {
            String message=error.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("cannot rollback - no transaction is active")
                    || message.contains("cannot commit - no transaction is active")
                    || message.contains("cannot start a transaction within a transaction"))
                return SQLExceptionOverride.Override.MUST_EVICT;
        }
        return SQLExceptionOverride.Override.CONTINUE_EVICT;
    }
}
