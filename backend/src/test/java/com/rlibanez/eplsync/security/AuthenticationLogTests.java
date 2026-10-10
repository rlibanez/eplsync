package com.rlibanez.eplsync.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class AuthenticationLogTests {
    final Account account = new Account("user-id", "Robert", "private@example.org", "USER", "ACTIVE", false, null, 1, Set.of(), null);
    final Logger logger = (Logger) LoggerFactory.getLogger(AuthenticationLog.class);
    ListAppender<ILoggingEvent> appender;
    @BeforeEach void start() {
        appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
    }
    @AfterEach void stop() {
        logger.detachAppender(appender); appender.stop();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }
    @Test void rejectedLoginsUseGenericReasonsAndNeverIncludePersonalData() {
        AuthenticationLog.rejected(false, "192.0.2.10"); AuthenticationLog.rejected(true, "2001:db8::10");
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain(account.email(), account.username());
        });
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Inicio de sesión rechazado: motivo=AUTHENTICATION_REJECTED, IP=192.0.2.10", "Inicio de sesión rechazado: motivo=RATE_LIMIT, IP=2001:db8::10");
    }
    @Test void invalidationsAreLoggedOnlyAfterCommitAndNotOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        AuthenticationLog.invalidated(account, "PASSWORD_CHANGED");
        assertThat(appender.list).isEmpty();
        // A rollback invokes no afterCommit callback.
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(appender.list).isEmpty();
        TransactionSynchronizationManager.initSynchronization();
        AuthenticationLog.invalidated(account, "PASSWORD_RESET");
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCommit());
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("userId=user-id", "usuario=Robert", "PASSWORD_RESET").doesNotContain(account.email());
        });
    }
}
