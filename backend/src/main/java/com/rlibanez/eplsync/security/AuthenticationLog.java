package com.rlibanez.eplsync.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Authentication audit in the application log only; never accepts credentials or request bodies. */
final class AuthenticationLog {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationLog.class);
    private AuthenticationLog() {}

    static void login(Account account, String clientIp) {
        LOG.info("Inicio de sesión: userId={}, usuario={}, IP={}", account.id(), account.username(), clientIp);
    }
    static void rejected(boolean rateLimited, String clientIp) {
        LOG.warn("Inicio de sesión rechazado: motivo={}, IP={}", rateLimited ? "RATE_LIMIT" : "AUTHENTICATION_REJECTED", clientIp);
    }
    static void logout(Account account) {
        LOG.info("Cierre de sesión explícito: userId={}, usuario={}", account.id(), account.username());
    }
    static void invalidated(Account account, String reason) {
        afterCommit(() -> LOG.info("Sesiones invalidadas: userId={}, usuario={}, motivo={}",
                account.id(), account.username(), reason));
    }
    static void allInvalidated() {
        afterCommit(() -> LOG.info("Sesiones invalidadas: alcance=TODAS_LAS_CUENTAS, motivo=DATABASE_RESET"));
    }
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
}
