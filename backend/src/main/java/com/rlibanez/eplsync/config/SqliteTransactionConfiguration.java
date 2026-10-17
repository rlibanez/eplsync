package com.rlibanez.eplsync.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/** SQLite has one writer. Acquire its turn before borrowing a pooled connection
 * or reading a snapshot that another writer could invalidate. */
@Configuration
public class SqliteTransactionConfiguration {
    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory factory) {
        return new SingleWriterTransactionManager(factory);
    }

    static final class SqliteJpaDialect extends org.springframework.orm.jpa.vendor.HibernateJpaDialect {
        @Override public Object beginTransaction(jakarta.persistence.EntityManager entityManager,
                TransactionDefinition definition) throws java.sql.SQLException {
            var session=entityManager.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
            var connection=session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection()
                    .unwrap(org.sqlite.SQLiteConnection.class);
            // The Java queue cannot cover JDBC autocommit writes or other processes.
            // Reserve SQLite's writer before the first SELECT, rather than upgrading
            // a DEFERRED snapshot after an intervening commit (BUSY_SNAPSHOT).
            connection.getConnectionConfig().setTransactionMode(definition.isReadOnly()
                    ? org.sqlite.SQLiteConfig.TransactionMode.DEFERRED
                    : org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
            return super.beginTransaction(entityManager,definition);
        }
    }

    static final class SingleWriterTransactionManager extends JpaTransactionManager {
        private final ReentrantLock writer = new ReentrantLock(true);
        private final Set<Object> owners = ConcurrentHashMap.newKeySet();
        SingleWriterTransactionManager(EntityManagerFactory factory) {
            super(factory);
            // Keep framework transaction traces under Spring's logging category.
            logger = org.apache.commons.logging.LogFactory.getLog(JpaTransactionManager.class);
        }

        @Override public void afterPropertiesSet() {
            super.afterPropertiesSet();
            // Spring obtains the provider dialect here; install ours afterwards.
            setJpaDialect(new SqliteJpaDialect());
        }

        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (!definition.isReadOnly()) {
                int seconds = definition.getTimeout() == TransactionDefinition.TIMEOUT_DEFAULT
                        ? 30 : Math.min(30, definition.getTimeout());
                try {
                    if (!writer.tryLock(seconds, TimeUnit.SECONDS))
                        throw new com.rlibanez.eplsync.ordering.SqliteContention.WriterBusy();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CannotCreateTransactionException("Escritura SQLite interrumpida", ex);
                }
                owners.add(transaction);
            }
            try { super.doBegin(transaction, definition); }
            catch (RuntimeException | Error ex) { release(transaction); throw ex; }
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            try { super.doCleanupAfterCompletion(transaction); }
            finally { release(transaction); }
        }
        private void release(Object transaction) {
            if (owners.remove(transaction)) writer.unlock();
        }
    }
}
