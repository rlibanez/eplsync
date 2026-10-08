package com.rlibanez.eplsync.config;

import java.time.Duration;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "eplsync.catalog.import")
@Validated @Getter @Setter
public class CatalogImportProperties {
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private transient java.util.function.Supplier<CatalogImportProperties> effectiveSupplier;
    public void useEffective(java.util.function.Supplier<CatalogImportProperties> supplier) { this.effectiveSupplier = supplier; }
    public CatalogImportProperties effective() { return effectiveSupplier == null ? this : effectiveSupplier.get(); }
    public Duration getRetention() { var current = effective(); return current == this ? retention : current.getRetention(); }

    // Installation resource budget; deliberately not a runtime UI setting.
    private Duration operationTimeout = Duration.ofMinutes(30);
    @AssertTrue(message = "import.operation-timeout debe ser positiva y no superar 24 horas")
    public boolean isOperationTimeoutValid() {
        return operationTimeout != null && operationTimeout.compareTo(Duration.ZERO)>0
            && operationTimeout.compareTo(Duration.ofDays(1))<=0;
    }
    private Duration retention = Duration.ofHours(24);
    @AssertTrue(message = "import.retention debe ser positiva y no superar 7 días")
    public boolean isRetentionValid() {
        return retention != null && retention.compareTo(Duration.ZERO) > 0 && retention.compareTo(Duration.ofDays(7)) <= 0;
    }
}
