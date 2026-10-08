package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CatalogImportProperties;
import com.rlibanez.eplsync.exception.CatalogOperationException;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogOperationGateTests {
    @Test void busyRequestsDoNotWaitOrRunWorkAndNestedCallsAreAllowed() throws Exception {
        var gate=new CatalogOperationGate(new CatalogImportProperties());
        try(var executor=Executors.newSingleThreadExecutor();var lease=gate.acquire()) {
            assertThat(gate.run(() -> 42)).isEqualTo(42);
            var result=executor.submit(() -> {
                assertThatThrownBy(() -> gate.run(() -> {throw new AssertionError("Rejected work executed");}))
                    .isInstanceOf(CatalogOperationException.class).hasMessageContaining("otra operación");
            });
            result.get(1,TimeUnit.SECONDS);
        }
        assertThat(gate.run(() -> 7)).isEqualTo(7);
    }
    @Test void failingWorkReleasesAdmissionAndInvalidConfigurationFailsValidation() {
        var properties=new CatalogImportProperties();var gate=new CatalogOperationGate(properties);
        assertThatThrownBy(() -> gate.run(() -> {throw new IllegalStateException("failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(gate.run(() -> 1)).isEqualTo(1);
        properties.setOperationTimeout(Duration.ZERO);assertThat(properties.isOperationTimeoutValid()).isFalse();
        properties.setOperationTimeout(Duration.ofDays(2));assertThat(properties.isOperationTimeoutValid()).isFalse();
    }
}
