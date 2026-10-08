package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.exception.CatalogOperationException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogOperationBudgetTests {
    @Test void phasesShareRemainingTimeAndExpiryDoesNotLeakToNextOperation() {
        var clock=new AtomicLong();
        try(var budget=new CatalogOperationBudget(Duration.ofSeconds(10),clock::get)) {
            assertThat(CatalogOperationBudget.remaining(Duration.ofMinutes(5))).isEqualTo(Duration.ofSeconds(10));
            clock.set(Duration.ofSeconds(7).toNanos());
            assertThat(CatalogOperationBudget.remaining(Duration.ofMinutes(2))).isEqualTo(Duration.ofSeconds(3));
            clock.set(Duration.ofSeconds(10).toNanos());
            assertThatThrownBy(CatalogOperationBudget::check).isInstanceOf(CatalogOperationException.class);
        }
        assertThatCode(CatalogOperationBudget::check).doesNotThrowAnyException();
    }
    @Test void interruptionIsNotConsumed() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(CatalogOperationBudget::check).isInstanceOf(CatalogOperationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void conversionFailuresCannotBypassTheDeadline() throws Exception {
        var clock=new AtomicLong();
        try(var budget=new CatalogOperationBudget(Duration.ofSeconds(1),clock::get);
            var reader=new CoverCsvReader(new java.io.BufferedReader(new java.io.StringReader("EPL Id,Revisión\ninvalid,wrong\n")))) {
            clock.set(Duration.ofSeconds(1).toNanos());
            assertThatThrownBy(() -> reader.read(new char[128])).isInstanceOf(CatalogOperationException.class);
        }
    }
}
