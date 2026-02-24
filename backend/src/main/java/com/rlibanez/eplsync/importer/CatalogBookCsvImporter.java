package com.rlibanez.eplsync.importer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.opencsv.bean.CsvToBeanBuilder;
import com.rlibanez.eplsync.dto.CatalogBookCsvRow;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.BookStatus;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.repository.CatalogBookRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Component
public class CatalogBookCsvImporter {

    private static final Logger log = LoggerFactory.getLogger(CatalogBookCsvImporter.class);

    private final CatalogBookRepository repository;

    private static final int BATCH_SIZE = 1000;
    private static final int PROGRESS_EVERY = 10000;

    @PersistenceContext
    private EntityManager em;

    public record ImportStats(int processed, int errors) {
    }

    public CatalogBookCsvImporter(CatalogBookRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ImportStats importFile(Path csvPath, boolean truncateBeforeImport) throws IOException {

        if (truncateBeforeImport) {
            repository.deleteAllInBatch();
            repository.flush();
            em.clear();
        }

        int processed = 0;
        int errors = 0;
        int rowNumber = 1;

        try (Reader reader = Files.newBufferedReader(csvPath)) {

            var it = new CsvToBeanBuilder<CatalogBookCsvRow>(reader)
                    .withType(CatalogBookCsvRow.class)
                    .withSeparator(',')
                    .withQuoteChar('"')
                    .withIgnoreLeadingWhiteSpace(true)
                    .build()
                    .iterator();

            List<CatalogBook> batch = new ArrayList<>(BATCH_SIZE);

            while (it.hasNext()) {
                rowNumber++;
                CatalogBookCsvRow row = null;
                try {
                    row = it.next();

                    var pub = PublicationFieldParser.parse(row.getPublishedRaw());

                    CatalogBook entity = CatalogBook.builder()
                            .eplId(row.getEplId())
                            .revision(row.getRevision())
                            .author(row.getAuthor())
                            .title(row.getTitle())
                            .genres(row.getGenres())
                            .collection(row.getCollection())
                            .volume(row.getVolume())
                            .publicationYear(row.getPublicationYear())
                            .synopsis(row.getSynopsis())
                            .pages(row.getPages())
                            .language(Language.fromString(row.getLanguage()))
                            .publicationStatus(pub.status())
                            .publicationDate(pub.date())
                            .status(BookStatus.fromString(row.getStatus()))
                            .rating(row.getRating())
                            .votesCount(row.getVotesCount())
                            .links(row.getLinks())
                            .build();

                    batch.add(entity);
                    processed++;

                    if (processed % PROGRESS_EVERY == 0) {
                        log.info("Progreso importación: {} filas OK, {} errores",
                                processed, errors);
                    }

                    if (batch.size() >= BATCH_SIZE) {
                        repository.saveAll(batch);
                        repository.flush();
                        em.clear();
                        batch.clear();
                    }

                } catch (Exception ex) {
                    errors++;
                    if (errors <= 20) {
                        Long eplId = (row != null) ? row.getEplId() : null;
                        log.warn("Error importando fila {} (eplId={}): {}", rowNumber, eplId, ex.getMessage());
                    }
                }
            }

            if (!batch.isEmpty()) {
                repository.saveAll(batch);
                repository.flush();
                em.clear();
                batch.clear();
            }
        }

        return new ImportStats(processed, errors);
    }
}
