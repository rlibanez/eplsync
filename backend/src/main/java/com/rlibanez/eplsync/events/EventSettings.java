package com.rlibanez.eplsync.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties("eplsync.events")
@Validated @Getter @Setter
public class EventSettings {
    @Valid private Retention retention = new Retention();
    @Getter @Setter
    public static class Retention {
        @Min(1) private int maxCount = 10000;
        @Min(1) private int maxAgeDays = 365;
    }
}
