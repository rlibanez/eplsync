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
    @lombok.Getter(lombok.AccessLevel.NONE) @lombok.Setter(lombok.AccessLevel.NONE)
    private transient java.util.function.Supplier<EventSettings> effectiveSupplier;
    public void useEffective(java.util.function.Supplier<EventSettings> supplier) { this.effectiveSupplier = supplier; }
    public EventSettings effective() { return effectiveSupplier == null ? this : effectiveSupplier.get(); }
    public Retention getRetention() { var current = effective(); return current == this ? retention : current.getRetention(); }

    @Valid private Retention retention = new Retention();
    @Getter @Setter
    public static class Retention {
        @Min(1) private int maxCount = 10000;
        @Min(1) private int maxAgeDays = 365;
    }
}
