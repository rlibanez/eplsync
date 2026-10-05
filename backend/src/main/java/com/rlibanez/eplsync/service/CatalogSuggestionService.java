package com.rlibanez.eplsync.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.text.Collator;
import java.text.Normalizer;
import java.util.*;

/** Cached vocabularies, not books or cached searches. Invalidated only after committed catalog changes. */
@Service
public class CatalogSuggestionService {
    private final EntityManager em;
    private final Map<String, List<Value>> cache = new HashMap<>();
    private record Value(String text, String normalized) {}
    public record Result(List<String> items, int total, Integer nextOffset) {}
    public CatalogSuggestionService(EntityManager em) { this.em = em; }

    public void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(); }
            });
        } else invalidate();
    }
    public synchronized void invalidate() { cache.clear(); }

    public Result suggest(String kind, String query, int offset) {
        if (!Set.of("titles", "authors", "collections", "genres").contains(kind)
                || query.length() > 512 || offset < 0)
            throw new com.rlibanez.eplsync.exception.UserInputException("Sugerencia, búsqueda o desplazamiento inválidos");
        String needle = normalize(query.strip());
        if (needle.length() < 2) return new Result(List.of(), 0, null);
        var matches = values(kind).stream().filter(v -> v.normalized().contains(needle)).toList();
        var items = matches.stream().skip(offset).limit(20).map(value -> Objects.requireNonNull(value).text()).toList();
        int next = offset + items.size();
        return new Result(items, matches.size(), next < matches.size() ? next : null);
    }
    private synchronized List<Value> values(String kind) {
        return cache.computeIfAbsent(kind, key -> {
            String field = switch (key) {
                case "titles" -> "title";
                case "authors" -> "author";
                case "collections" -> "collection";
                case "genres" -> "genres";
                default -> throw new com.rlibanez.eplsync.exception.UserInputException("Campo inválido");
            };
            var source = em.createQuery("select distinct b." + field + " from CatalogBook b where b." + field + " is not null", String.class)
                .getResultList();
            Set<String> unique = new HashSet<>();
            for (String text : source)
                for (String value : (key.equals("collections") || key.equals("titles")) ? new String[]{text} : text.split(key.equals("authors") ? "&" : ","))
                    if (!value.isBlank()) unique.add(value.strip());
            var collator = Collator.getInstance(Locale.forLanguageTag("es"));
            return unique.stream().sorted(Comparator.comparing((String v) -> v, collator).thenComparing(Comparator.naturalOrder()))
                .map(text -> new Value(text, normalize(text))).toList();
        });
    }
    private static String normalize(String text) {
        String protectedText = Normalizer.normalize(text, Normalizer.Form.NFC).toUpperCase(Locale.ROOT).replace("Ñ", "\uE000");
        return Normalizer.normalize(protectedText, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace("\uE000", "Ñ");
    }
}
