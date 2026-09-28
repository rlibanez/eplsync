package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.dto.PageResponse;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.*;

@Service
public class DownloadQueryService {
    private static final Set<String> DATE_FIELDS = Set.of("createdAt", "requestedAt", "submittedAt", "completedAt", "discoveredAt", "lastCheckedAt", "lastSeenAt");
    private static final Set<String> SORT_FIELDS = Set.of("id", "eplId", "revision", "hash", "status", "origin", "client", "clientInstanceId",
            "createdAt", "requestedAt", "submittedAt", "completedAt", "discoveredAt", "lastCheckedAt", "lastSeenAt");
    private final DownloadRepository repository;
    private final jakarta.persistence.EntityManager entityManager;
    public DownloadQueryService(DownloadRepository repository, jakarta.persistence.EntityManager entityManager) {
        this.repository = repository; this.entityManager = entityManager;
    }

    public record Summary(long total, Map<DownloadStatus, Long> byStatus) {}

    @Transactional(readOnly = true)
    public Summary summary(MultiValueMap<String, String> params) {
        validate(params, false);
        var spec = filters(params);
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(DownloadRecord.class);
        var state = root.<DownloadStatus>get("status");
        query.select(cb.tuple(state, cb.count(root)));
        var predicate = spec.toPredicate(root, query, cb);
        if (predicate != null) query.where(predicate);
        query.groupBy(state);
        var counts = new EnumMap<DownloadStatus, Long>(DownloadStatus.class);
        for (var status : DownloadStatus.values()) counts.put(status, 0L);
        long total = 0;
        for (var row : entityManager.createQuery(query).getResultList()) {
            long count = row.get(1, Long.class);
            counts.put(row.get(0, DownloadStatus.class), count);
            total += count;
        }
        return new Summary(total, Collections.unmodifiableMap(counts));
    }

    @Transactional(readOnly = true)
    public PageResponse<DownloadRecord> search(MultiValueMap<String, String> params) {
        validate(params, true);
        int page = integer(params.getFirst("page"), 0), size = integer(params.getFirst("size"), 20);
        if (page < 0 || size < 1) throw new IllegalArgumentException("page debe ser >= 0 y size debe ser > 0");
        var orders = new ArrayList<Sort.Order>();
        for (var value : params.getOrDefault("sort", List.of("createdAt,desc"))) {
            var parts = value.split(",", -1);
            if (parts.length > 2 || !SORT_FIELDS.contains(parts[0])) throw new IllegalArgumentException("Ordenación de descargas inválida");
            orders.add(new Sort.Order(parts.length == 1 ? Sort.Direction.ASC : Sort.Direction.fromString(parts[1]), parts[0]));
        }
        if (orders.stream().noneMatch(order -> order.getProperty().equals("id"))) orders.add(Sort.Order.asc("id"));
        var spec = filters(params);
        var result = repository.findAll(spec, PageRequest.of(page, size, Sort.by(orders)));
        return new PageResponse<>(result.getContent(), new PageResponse.PageMeta(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast(), result.hasNext(), result.hasPrevious()));
    }

    private void validate(MultiValueMap<String, String> params, boolean listing) {
        var allowed = new HashSet<>(Set.of("eplId", "hash", "revision", "status", "origin", "client", "clientInstanceId", "completed"));
        if (listing) allowed.addAll(Set.of("page", "size", "sort"));
        DATE_FIELDS.forEach(field -> { allowed.add(field + "From"); allowed.add(field + "To"); });
        if (!allowed.containsAll(params.keySet())) throw new IllegalArgumentException("Filtro de descargas desconocido");
        params.forEach((key, values) -> {
            if (!key.equals("sort") && values.size() != 1) throw new IllegalArgumentException("Parámetro repetido: " + key);
            if (values.stream().anyMatch(value -> value == null || value.isBlank())) throw new IllegalArgumentException("Parámetro vacío: " + key);
        });
    }

    private Specification<DownloadRecord> filters(MultiValueMap<String, String> params) {
        // Parsear antes de construir la consulta, incluso con una tabla vacía.
        var values = new LinkedHashMap<String, Object>();
        for (var entry : params.entrySet()) {
            var key = entry.getKey(); var text = entry.getValue().getFirst();
            try {
                switch (key) {
                    case "page", "size", "sort" -> { }
                    case "eplId" -> { long id = Long.parseLong(text); if (id < 1) throw new IllegalArgumentException(); values.put(key, id); }
                    case "revision" -> { double revision = Double.parseDouble(text); if (!Double.isFinite(revision) || revision < 0) throw new IllegalArgumentException(); values.put(key, revision); }
                    case "completed" -> { if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException(); values.put(key, Boolean.valueOf(text)); }
                    case "status" -> values.put(key, Arrays.stream(text.split(",", -1)).map(DownloadStatus::valueOf).toList());
                    case "origin" -> values.put(key, Arrays.stream(text.split(",", -1)).map(DownloadRecord.Origin::valueOf).toList());
                    case "hash" -> { if (!text.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalArgumentException(); values.put(key, text.toUpperCase(Locale.ROOT)); }
                    default -> values.put(key, key.endsWith("From") || key.endsWith("To") ? Instant.parse(text) : text);
                }
            } catch (RuntimeException ex) { throw new IllegalArgumentException("Valor inválido para " + key); }
        }
        DATE_FIELDS.forEach(field -> {
            var from = (Instant) values.get(field + "From"); var to = (Instant) values.get(field + "To");
            if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Intervalo inválido para " + field);
        });
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            values.forEach((key, value) -> {
                if (key.equals("completed")) predicates.add((Boolean) value ? cb.isNotNull(root.get("completedAt")) : cb.isNull(root.get("completedAt")));
                else if (key.equals("status") || key.equals("origin")) predicates.add(root.get(key).in((List<?>) value));
                else if (key.endsWith("From")) predicates.add(cb.greaterThanOrEqualTo(root.get(key.substring(0, key.length() - 4)), (Instant) value));
                else if (key.endsWith("To")) predicates.add(cb.lessThanOrEqualTo(root.get(key.substring(0, key.length() - 2)), (Instant) value));
                else predicates.add(cb.equal(root.get(key), value));
            });
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Paginación inválida"); }
    }
}
