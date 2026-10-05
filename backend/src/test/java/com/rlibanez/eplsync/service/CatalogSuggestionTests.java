package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.importer.CatalogBookCsvImporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.security.test.context.support.WithMockUser(authorities={"ROLE_ADMIN","CATALOG_READ","BOOK_HISTORY_READ","DOWNLOADS_READ","TORRENT_SEND","TORRENT_SYNC","TORRENT_JOBS_MANAGE","TORRENT_CLEANUP","TORRENT_FILES_DELETE","CATALOG_IMPORT","CATALOG_DELETE","COVERS_MANAGE","EVENTS_MANAGE","SETTINGS_MANAGE"})
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite::memory:","spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","eplsync.torrent.enabled=false","eplsync.torrent.bulk.worker-enabled=false"})
class CatalogSuggestionTests {
    @Autowired CatalogSuggestionService suggestions;
    @Autowired CatalogBookRepository books;
    @Autowired CatalogBookCsvImporter importer;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @TempDir Path directory;

    @BeforeEach void clear() { books.deleteAllInBatch(); suggestions.invalidate(); }
    private CatalogBook book(long id, String author) {
        return CatalogBook.builder().eplId(id).title("Book").author(author).revision(1.0)
            .genres("Drama, Fantasía, Drama").collection("Una colección & otra").build();
    }
    @Test void splitsDeduplicatesAndSearchesAccentsWithoutSplittingCollections() {
        books.saveAndFlush(book(1, "AA. VV. & García Márquez & García Márquez"));
        assertThat(suggestions.suggest("authors", "garcia", 0).items()).containsExactly("García Márquez");
        assertThat(suggestions.suggest("genres", "fantasia", 0).items()).containsExactly("Fantasía");
        assertThat(suggestions.suggest("collections", "coleccion", 0).items()).containsExactly("Una colección & otra");
        assertThat(suggestions.suggest("authors", "a", 0).items()).isEmpty();
    }
    @Test void pagesAllMatchesInStableBatchesOfTwenty() {
        for (int i=0;i<45;i++) books.save(book(i+1, "Autor %02d".formatted(i)));
        var first = suggestions.suggest("authors", "autor", 0);
        assertThat(first.total()).isEqualTo(45);
        assertThat(first.items()).hasSize(20).startsWith("Autor 00").endsWith("Autor 19");
        assertThat(first.nextOffset()).isEqualTo(20);
        assertThat(suggestions.suggest("authors","autor",20).items()).hasSize(20).startsWith("Autor 20");
        var last = suggestions.suggest("authors","autor",40);
        assertThat(last.items()).hasSize(5); assertThat(last.nextOffset()).isNull();
    }
    @Test void committedImportsRefreshCacheButPreviewAndRollbackDoNot() throws Exception {
        Path csv = directory.resolve("catalog.csv");
        Files.writeString(csv, "EPL Id,Revisión,Autor,Título\n1,1,Original,Book\n");
        importer.importFile(csv, true);
        assertThat(suggestions.suggest("authors","original",0).items()).hasSize(1);
        Files.writeString(csv, "EPL Id,Revisión,Autor,Título\n1,2,Modificado,Book\n");
        importer.previewFile(csv,0,20);
        assertThat(suggestions.suggest("authors","original",0).items()).hasSize(1);
        importer.importFile(csv,false);
        assertThat(suggestions.suggest("authors","original",0).items()).isEmpty();
        assertThat(suggestions.suggest("authors","modificado",0).items()).hasSize(1);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            books.deleteAllInBatch(); suggestions.invalidateAfterCommit(); tx.setRollbackOnly();
        });
        assertThat(suggestions.suggest("authors","modificado",0).items()).hasSize(1);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            books.deleteAllInBatch(); suggestions.invalidateAfterCommit();
        });
        assertThat(suggestions.suggest("authors","modificado",0).items()).isEmpty();
    }
    @Test void endpointValidatesAndReturnsOnlyText() throws Exception {
        books.saveAndFlush(book(1,"Autor"));
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/catalog/suggestions/authors").param("q","au"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0]").value("Autor"))
            .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/catalog/suggestions/links").param("q","ab")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/suggestions/authors").param("offset","-1")).andExpect(status().isBadRequest());
    }
    @Test void titlesRemainWholeAndRefreshAfterImport() throws Exception {
        var first = book(1, "Autor");
        first.setTitle("Corazón, ciencia & ficción");
        books.saveAndFlush(first);
        var duplicate = book(2, "Otro autor");
        duplicate.setTitle(first.getTitle());
        books.saveAndFlush(duplicate);
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/catalog/suggestions/titles").param("q", "corazon"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0]").value(first.getTitle()))
            .andExpect(jsonPath("$.total").value(1));
        Path csv = directory.resolve("titles.csv");
        Files.writeString(csv, "EPL Id,Revisión,Autor,Título\n1,2,Autor,Título nuevo\n");
        importer.importFile(csv, true);
        assertThat(suggestions.suggest("titles", "corazon", 0).items()).isEmpty();
        assertThat(suggestions.suggest("titles", "titulo", 0).items()).containsExactly("Título nuevo");
    }
}
