package com.rlibanez.eplsync.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class MaintenanceGateTests {
    @Test void rejectsResetDuringAnExistingApiRequestAndReleasesOnFailure() throws Exception {
        var gate = new MaintenanceGate(); var filter = new MaintenanceFilter(gate);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var existing = executor.submit(() -> {
                var request = new MockHttpServletRequest("POST", "/api/catalog/import/update");
                request.setServletPath("/api/catalog/import/update");
                try { filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
                    entered.countDown();
                    try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    throw new jakarta.servlet.ServletException("test failure");
                }); } catch (Exception expected) { /* Ensure the gate is released on errors. */ }
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                var request = new MockHttpServletRequest("POST", "/api/maintenance/reset"); request.setServletPath("/api/maintenance/reset");
                var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
                filter.doFilter(request,response,(req,res)->called.set(true));
                assertThat(response.getStatus()).isEqualTo(409); assertThat(called).isFalse();
            } finally {release.countDown();}
            existing.get(5,TimeUnit.SECONDS);
            assertThat(gate.enter(true)).isTrue(); gate.leave(true);
        }
    }
    @Test void rejectsNewApiRequestsWhileResetIsRunning() throws Exception {
        var gate = new MaintenanceGate(); var filter = new MaintenanceFilter(gate);
        assertThat(gate.enter(true)).isTrue();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var result = executor.submit(() -> {
                    var request = new MockHttpServletRequest("POST", "/api/torrent/books/1"); request.setServletPath("/api/torrent/books/1");
                    var response = new MockHttpServletResponse(); filter.doFilter(request,response,(req,res)->{throw new AssertionError("Must not enter");});
                    return response.getStatus();
                });
                assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo(409);
            } finally {gate.leave(true);}
        }
        assertThat(gate.enter(false)).isTrue(); gate.leave(false);
    }
}
