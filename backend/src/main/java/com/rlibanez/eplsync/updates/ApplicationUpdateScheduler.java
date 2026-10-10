package com.rlibanez.eplsync.updates;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="eplsync.updates.scheduler-enabled",matchIfMissing=true)
class ApplicationUpdateScheduler {
    private final ApplicationUpdates updates;
    ApplicationUpdateScheduler(ApplicationUpdates updates) {this.updates=updates;}
    @org.springframework.scheduling.annotation.Scheduled(initialDelay=10000,fixedDelay=60000)
    void tick() {updates.check(true);}
}
