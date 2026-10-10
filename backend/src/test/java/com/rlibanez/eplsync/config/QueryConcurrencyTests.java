package com.rlibanez.eplsync.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class QueryConcurrencyTests {
    @Test void excessQueriesAreRejectedWithBoundedWaitingAndSlotsAreReleasedExactlyOnce() throws Exception {
        var interceptor = new QueryConcurrencyConfiguration().boundedQueries();
        var response = new MockHttpServletResponse();
        var requests = new java.util.ArrayList<MockHttpServletRequest>();
        for (int i=0;i<4;i++) {
            var request = new MockHttpServletRequest("POST", "/api/catalog/books"); requests.add(request);
            assertThat(interceptor.preHandle(request,response,new Object())).isTrue();
        }
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest("POST", "/api/catalog/books"),response,new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        interceptor.afterCompletion(requests.getFirst(),response,new Object(),new IllegalStateException("Failed query"));
        interceptor.afterCompletion(requests.getFirst(),response,new Object(),null);
        assertThat(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/catalog/books"),response,new Object())).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest("POST", "/api/catalog/books"),response,new Object()))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void readBurstWaitsForReleasedSlot() throws Exception {
        var interceptor = new QueryConcurrencyConfiguration().boundedQueries();
        var response = new MockHttpServletResponse();
        var held = new java.util.ArrayList<MockHttpServletRequest>();
        for (int i=0;i<4;i++) {
            var request = new MockHttpServletRequest("GET", "/api/catalog/books");
            held.add(request); interceptor.preHandle(request,response,new Object());
        }
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var next = new MockHttpServletRequest("GET", "/api/home/summary");
            var result = executor.submit(() -> interceptor.preHandle(next,response,new Object()));
            assertThatThrownBy(() -> result.get(50,java.util.concurrent.TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
            interceptor.afterCompletion(held.getFirst(),response,new Object(),null);
            assertThat(result.get(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            interceptor.afterCompletion(next,response,new Object(),null);
        }
    }
    @Test void readsTimeOutRatherThanWaitIndefinitely() throws Exception {
        var interceptor = new QueryConcurrencyConfiguration().boundedQueries();
        var response = new MockHttpServletResponse();
        for(int i=0;i<4;i++) interceptor.preHandle(new MockHttpServletRequest("GET", "/api/catalog/books"),response,new Object());
        long start=System.nanoTime();
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest("GET", "/api/home/summary"),response,new Object()))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isBetween(java.time.Duration.ofMillis(1500),java.time.Duration.ofSeconds(4));
    }
    @Test void waitingQueueRejectsExcessReadersWithoutAccumulatingThreads() throws Exception {
        var interceptor=new QueryConcurrencyConfiguration().boundedQueries();
        for(int i=0;i<4;i++) interceptor.preHandle(new MockHttpServletRequest("GET","/api/catalog/books"),new MockHttpServletResponse(),new Object());
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(12)) {
            var results=new java.util.concurrent.ExecutorCompletionService<Integer>(executor);
            var ready=new java.util.concurrent.CyclicBarrier(12);
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<12;i++) futures.add(results.submit(() -> {
                ready.await();
                try { interceptor.preHandle(new MockHttpServletRequest("GET","/api/home/summary"),new MockHttpServletResponse(),new Object()); return 200; }
                catch(ResponseStatusException ex) { return ex.getStatusCode().value(); }
            }));
            try {
                for(int i=0;i<4;i++) {
                    var rejected=results.poll(1,java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(rejected).isNotNull(); assertThat(rejected.get()).isEqualTo(429);
                }
                assertThat(results.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
            } finally { futures.forEach(future -> future.cancel(true)); }
        }
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
