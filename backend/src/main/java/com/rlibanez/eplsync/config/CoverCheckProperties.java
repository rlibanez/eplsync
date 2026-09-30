package com.rlibanez.eplsync.config;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "eplsync.catalog.cover-check")
@Validated
@Getter
@Setter
public class CoverCheckProperties {
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(3);
    private Duration batchTimeout = Duration.ofSeconds(4);
    @Min(1) @Max(32)
    private int concurrency = 4;

    @AssertTrue(message = "Los timeouts de portadas deben estar entre 1ms y 5min, con connect-timeout <= request-timeout <= batch-timeout")
    public boolean isTimeoutConfigurationValid() {
        return valid(connectTimeout) && valid(requestTimeout) && valid(batchTimeout)
                && connectTimeout.compareTo(requestTimeout) <= 0
                && requestTimeout.compareTo(batchTimeout) <= 0;
    }

    private boolean valid(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofMinutes(5)) <= 0;
    }
}
