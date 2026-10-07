package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.torrent.bulk.BulkStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ImmediateUpdateCleanupTests.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ImmediateUpdateCleanupTests {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor mocks() {
            return factory -> {
                factory.registerSingleton("queue",mock(CleanupQueue.class));
                factory.registerSingleton("bulk",mock(BulkStore.class));
            };
        }
        @Bean ImmediateUpdateCleanup immediate(CleanupQueue queue,BulkStore bulk) {return new ImmediateUpdateCleanup(queue,bulk);}
        @Bean PlatformTransactionManager manager() {return new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:sqlite::memory:"));}
    }
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager manager;
    @Autowired CleanupQueue queue;

    @Test void acceptedSubmissionRunsCleanupOnlyAfterCommitAndDrainsBoundedBatches() {
        when(queue.runImmediate(eq(100),any())).thenReturn(100,0);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            publisher.publishEvent(new ImmediateUpdateCleanup.SubmissionAccepted());
            verifyNoInteractions(queue);
        });
        verify(queue,timeout(2000).times(2)).runImmediate(eq(100),any());
    }
    @Test void rolledBackSubmissionDoesNotTriggerRemoval() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            publisher.publishEvent(new ImmediateUpdateCleanup.SubmissionAccepted());
            status.setRollbackOnly();
        });
        verifyNoInteractions(queue);
    }
}
