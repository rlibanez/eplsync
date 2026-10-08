package com.rlibanez.eplsync.security;

import com.rlibanez.eplsync.config.CatalogImportProperties;
import com.rlibanez.eplsync.service.CatalogOperationGate;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogImportAdmissionFilterTests {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void busyUploadIsRejectedBeforeReadingOrCreatingParts() throws Exception {
        var gate=new CatalogOperationGate(new CatalogImportProperties());
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(() -> {try(var lease=gate.acquire()) {held.countDown();release.await();}catch(InterruptedException ex) {Thread.currentThread().interrupt();}});
            try {
                assertThat(held.await(1,TimeUnit.SECONDS)).isTrue();
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin","unused","CATALOG_IMPORT"));
                var request=spy(new MockHttpServletRequest("POST","/api/catalog/import/run"));request.setContentType("multipart/form-data; boundary=test");
                var response=new MockHttpServletResponse();
                new CatalogImportAdmissionFilter(gate).doFilter(request,response,(req,res) -> {throw new AssertionError("Rejected chain executed");});
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(response.getContentAsString()).contains("otra operación");
                verify(request,never()).getParts();verify(request,never()).getInputStream();
            } finally { release.countDown();holder.get(1,TimeUnit.SECONDS); }
        }
    }
    @Test void unauthorizedUploadNeverParsesMultipart() throws Exception {
        var request=spy(new MockHttpServletRequest("POST","/api/catalog/import/run"));
        var response=new MockHttpServletResponse();
        new CatalogImportAdmissionFilter(new CatalogOperationGate(new CatalogImportProperties())).doFilter(request,response,(req,res) -> {throw new AssertionError("Unauthorized chain executed");});
        assertThat(response.getStatus()).isEqualTo(401);verify(request,never()).getParts();verify(request,never()).getInputStream();
    }
}
