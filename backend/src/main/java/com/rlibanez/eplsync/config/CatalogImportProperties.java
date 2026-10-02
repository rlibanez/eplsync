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
    private Duration retention = Duration.ofHours(24);
    @AssertTrue(message = "import.retention debe ser positiva y no superar 7 días")
    public boolean isRetentionValid() {
        return retention != null && retention.compareTo(Duration.ZERO) > 0 && retention.compareTo(Duration.ofDays(7)) <= 0;
    }
}
