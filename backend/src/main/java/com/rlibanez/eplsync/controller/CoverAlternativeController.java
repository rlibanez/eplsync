package com.rlibanez.eplsync.controller;

import com.rlibanez.eplsync.repository.CatalogBookRepository;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog/covers")
public class CoverAlternativeController {
    private final CatalogBookRepository repository;
    private final TransactionTemplate transactions;
    public CoverAlternativeController(CatalogBookRepository repository, TransactionTemplate transactions) {
        this.repository = repository; this.transactions = transactions;
    }
    public record Request(String expectedCoverUrl) {}
    public record Result(long eplId, boolean coverAvailable) {}

    @PostMapping("/{eplId}/alternative")
    public ResponseEntity<?> alternative(@PathVariable long eplId, @RequestBody Request request) {
        if (request.expectedCoverUrl() == null || request.expectedCoverUrl().isBlank())
            throw new IllegalArgumentException("expectedCoverUrl es obligatorio");
        return transactions.execute(status -> {
            var book = repository.findById(eplId).orElse(null);
            if (book == null) return ResponseEntity.notFound().build();
            if (!Objects.equals(book.getCoverUrl(), request.expectedCoverUrl()))
                return ResponseEntity.status(409).body(java.util.Map.of("code", "COVER_CHANGED"));
            if (!Boolean.FALSE.equals(book.getCoverAvailable()) && repository.updateCoverAvailability(eplId,
                    book.getCoverUrl(), book.getCoverAvailable(), false) != 1)
                return ResponseEntity.status(409).body(java.util.Map.of("code", "COVER_CHANGED"));
            return ResponseEntity.ok(new Result(eplId, false));
        });
    }
}
