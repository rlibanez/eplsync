package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.*;
import com.rlibanez.eplsync.torrent.MagnetLinkBuilder;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
@Transactional
class MagnetExportTests {
    @Autowired CatalogBookRepository books;
    @Autowired CatalogMetadataRepository metadata;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired MagnetLinkBuilder builder;
    @Autowired PlatformTransactionManager manager;
    @Autowired MagnetExportService exports;
    private MagnetExportService limited(long bytes, long index, java.util.function.LongSupplier nanos) {
        return new MagnetExportService(em, builder, metadata, manager,
                new MagnetExportService.Limits(bytes,index,Duration.ofMinutes(2),Duration.ofMinutes(2)), nanos);
    }
    @BeforeEach void seed() {
        books.deleteAllInBatch();
        for (long id=1; id<=601; id++) books.save(CatalogBook.builder().eplId(id).revision(1.0).title("Book "+id)
                .author("Author").links(String.format("%040X", id) + "; " + "F".repeat(40)).build());
        books.flush(); em.clear();
    }
    @Test void exportsAllBatchesInRequestedOrderAndDeduplicatesWithoutManagedEntities() throws Exception {
        try (var prepared = exports.prepare(new CatalogBookFilter(), Sort.by(Sort.Direction.DESC,"eplId"))) {
            var result = new ByteArrayOutputStream();
            prepared.writeTo(result);
            var lines = result.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            assertThat(lines).hasSize(602);
            assertThat(lines.getFirst()).contains("dn=EPL_601_Book%20601");
            assertThat(lines.get(1)).contains("F".repeat(40)).contains("EPL_601");
            assertThat(lines.getLast()).contains("dn=EPL_1_Book%201");
            assertThat(em.unwrap(org.hibernate.engine.spi.SessionImplementor.class).getPersistenceContext()
                    .getNumberOfManagedEntities()).isZero();
        }
    }
    @Autowired javax.sql.DataSource datasource;
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void preparedSnapshotDoesNotHoldTheCatalogConnectionDuringTransfer() throws Exception {
        try (var prepared=exports.prepare(new CatalogBookFilter(),Sort.unsorted())) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try (var connection=datasource.getConnection(); var statement=connection.createStatement();
                     var result=statement.executeQuery("SELECT count(*) FROM catalog_books")) {
                    assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isEqualTo(601);
                }
            });
            var output=new ByteArrayOutputStream(); prepared.writeTo(output); assertThat(output.size()).isPositive();
        }
    }

    @Test void secondExportIsRejectedUntilTheFirstIsClosed() throws Exception {
        var first = exports.prepare(new CatalogBookFilter(),Sort.unsorted());
        try {
            assertThatThrownBy(() -> exports.prepare(new CatalogBookFilter(),Sort.unsorted()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
        } finally { first.close(); first.close(); }
        try (var next = exports.prepare(new CatalogBookFilter(),Sort.unsorted())) { assertThat(next.size()).isPositive(); }
    }
    @Test void outputAndIndexLimitsRejectBeforeStreamingAndReleaseSlot() throws Exception {
        for (var service : java.util.List.of(limited(10,128L*1024*1024,System::nanoTime),limited(128L*1024*1024,4096,System::nanoTime))) {
            assertThatThrownBy(() -> service.prepare(new CatalogBookFilter(),Sort.unsorted()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(413));
            // Rejection releases the lease, so the next attempt hits the same limit rather than HTTP 429.
            assertThatThrownBy(() -> service.prepare(new CatalogBookFilter(),Sort.unsorted()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(413));
        }
    }
    @Test void preparationDeadlineRejectsAndReleasesSlot() {
        var ticks = new AtomicLong();
        var service = limited(128L*1024*1024,128L*1024*1024,() -> ticks.getAndAdd(Duration.ofMinutes(3).toNanos()));
        for (int i=0;i<2;i++) assertThatThrownBy(() -> service.prepare(new CatalogBookFilter(),Sort.unsorted()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(408));
    }
    @Test void changedCatalogVersionAbortsBeforePublishingTheSnapshot() {
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var changingBuilder = org.mockito.Mockito.mock(MagnetLinkBuilder.class);
        org.mockito.Mockito.when(changingBuilder.hashes(org.mockito.Mockito.any())).thenAnswer(invocation -> {
            if (changed.compareAndSet(false,true)) {
                var value = new com.rlibanez.eplsync.model.CatalogMetadata();
                value.setImportedAt(java.time.Instant.now()); value.setSourceSha256("changed"); metadata.saveAndFlush(value);
            }
            return builder.hashes(invocation.getArgument(0));
        });
        org.mockito.Mockito.when(changingBuilder.build(org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any()))
                .thenAnswer(invocation -> builder.build(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2)));
        var service = new MagnetExportService(em,changingBuilder,metadata,manager);
        assertThatThrownBy(() -> service.prepare(new CatalogBookFilter(),Sort.unsorted()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }
    @Test void transferDeadlineClosesSnapshotAndReleasesSlot() throws Exception {
        var service = new MagnetExportService(em,builder,metadata,manager,
                new MagnetExportService.Limits(128L*1024*1024,128L*1024*1024,Duration.ofMinutes(2),Duration.ZERO),System::nanoTime);
        var prepared=service.prepare(new CatalogBookFilter(),Sort.unsorted());
        assertThatThrownBy(() -> prepared.writeTo(new ByteArrayOutputStream())).isInstanceOf(IOException.class)
                .hasMessage("Tiempo máximo de transferencia superado");
        assertThatThrownBy(prepared::size).isInstanceOf(java.nio.file.NoSuchFileException.class);
        try (var next=service.prepare(new CatalogBookFilter(),Sort.unsorted())) { assertThat(next.size()).isPositive(); }
    }
    @Test void networkFailureCleansSnapshotAndPermitsAnotherExport() throws Exception {
        var prepared = exports.prepare(new CatalogBookFilter(),Sort.unsorted());
        assertThatThrownBy(() -> prepared.writeTo(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        })).isInstanceOf(IOException.class);
        assertThatThrownBy(prepared::size).isInstanceOf(java.nio.file.NoSuchFileException.class);
        try (var next=exports.prepare(new CatalogBookFilter(),Sort.unsorted())) { assertThat(next.size()).isPositive(); }
    }
    @Test void cancellationRetainsTheSlotWhileNetworkWriterIsBlocked() throws Exception {
        var prepared = exports.prepare(new CatalogBookFilter(),Sort.unsorted());
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var transfer = executor.submit(() -> {
                try { prepared.writeTo(new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        entered.countDown();
                        try { if (!finish.await(5,TimeUnit.SECONDS)) throw new IOException("Test deadline"); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    }
                }); } catch (IOException expected) { }
            });
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                prepared.close();
                assertThatThrownBy(() -> exports.prepare(new CatalogBookFilter(),Sort.unsorted()))
                        .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
            } finally { finish.countDown(); }
            transfer.get(5,TimeUnit.SECONDS);
        } finally { prepared.close(); }
        try (var next=exports.prepare(new CatalogBookFilter(),Sort.unsorted())) { assertThat(next.size()).isPositive(); }
    }
}
