package com.rlibanez.eplsync.model;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CatalogCoverSchemaTests {
    @TempDir Path directory;

    @Test
    void schemaUpdateAddsNullableCoverToExistingDatabaseWithoutLosingBooks() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("existing.db");
        // A database created before cover_url existed.
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE catalog_books (
                      epl_id BIGINT PRIMARY KEY, revision DOUBLE NOT NULL,
                      author VARCHAR(255) NOT NULL, title VARCHAR(512) NOT NULL,
                      genres VARCHAR(512), collection VARCHAR(255), volume DOUBLE,
                      publication_year INTEGER, synopsis TEXT, pages INTEGER,
                      language VARCHAR(50), publication_status VARCHAR(50),
                      publication_date DATE, insert_date TIMESTAMP, last_modified_date TIMESTAMP,
                      status VARCHAR(50), rating DOUBLE, votes_count INTEGER, links TEXT
                    )
                    """);
            sql.execute("INSERT INTO catalog_books (epl_id, revision, author, title) VALUES (32, 1, 'Autor', 'Libro')");
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.driver_class", "org.sqlite.JDBC")
                .applySetting("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect")
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .build();
        try (var factory = new MetadataSources(registry).addAnnotatedClass(CatalogBook.class)
                .buildMetadata().buildSessionFactory(); var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            var book = session.find(CatalogBook.class, 32L);
            assertThat(book.getTitle()).isEqualTo("Libro");
            assertThat(book.getCoverUrl()).isNull();
            assertThat(book.getCoverAvailable()).isNull();
            book.setCoverUrl("https://example.org/32.jpg");
            book.setCoverAvailable(false);
            transaction.commit();
            session.clear();
            assertThat(session.find(CatalogBook.class, 32L).getCoverUrl()).isEqualTo("https://example.org/32.jpg");
            assertThat(session.find(CatalogBook.class, 32L).getCoverAvailable()).isFalse();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
