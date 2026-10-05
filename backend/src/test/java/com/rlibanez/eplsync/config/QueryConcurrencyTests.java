package com.rlibanez.eplsync.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class QueryConcurrencyTests {
    @Test void excessQueriesAreRejectedWithoutQueueingAndSlotsAreReleasedExactlyOnce() throws Exception {
        var interceptor = new QueryConcurrencyConfiguration().boundedQueries();
        var response = new MockHttpServletResponse();
        var requests = new java.util.ArrayList<MockHttpServletRequest>();
        for (int i=0;i<4;i++) {
            var request = new MockHttpServletRequest(); requests.add(request);
            assertThat(interceptor.preHandle(request,response,new Object())).isTrue();
        }
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(),response,new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        interceptor.afterCompletion(requests.getFirst(),response,new Object(),new IllegalStateException("Failed query"));
        interceptor.afterCompletion(requests.getFirst(),response,new Object(),null);
        assertThat(interceptor.preHandle(new MockHttpServletRequest(),response,new Object())).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(),response,new Object()))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void transferExecutorHasOneThreadAndNoPendingQueue() throws Exception {
        var executor = new QueryConcurrencyConfiguration().exportStreamingExecutor(); executor.initialize();
        var entered = new java.util.concurrent.CountDownLatch(1); var finish = new java.util.concurrent.CountDownLatch(1);
        try {
            executor.execute(() -> { entered.countDown(); try { finish.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
        } finally { finish.countDown(); executor.shutdown(); }
    }
    @Test void transferErrorsDoNotRetainFileHeadersOrAppendJsonToCommittedDownloads() {
        var handler=new com.rlibanez.eplsync.exception.GlobalExceptionHandler();
        var request=new MockHttpServletRequest("GET","/api/catalog/magnets/export");
        var response=new MockHttpServletResponse();
        response.setHeader("Content-Length","10000"); response.setHeader("Content-Disposition","attachment");
        var result=handler.handleTransferFailure(new java.io.IOException("Timeout"),request,response);
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("Content-Disposition")).isNull();
        response.setCommitted(true);
        assertThat(handler.handleTransferFailure(new java.io.IOException("Disconnect"),request,response)).isNull();
    }

}
