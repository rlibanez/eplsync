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
    private static final Set<String> MAIN_EXTENSIONS=Set.of("epub","azw3","mobi","pdf","rar","zip","7z","cbr");
    private static final Set<String> DATE_FIELDS = Set.of("createdAt", "requestedAt", "submittedAt", "completedAt", "discoveredAt", "lastCheckedAt", "lastSeenAt");
    private static final Set<String> SORT_FIELDS = Set.of("fileExtensions", "author", "title", "lastError", "id", "eplId", "revision", "hash", "status", "origin", "client", "clientInstanceId",
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
        com.rlibanez.eplsync.config.QueryLimits.page(page, size);
        var orders = new ArrayList<Sort.Order>();
        for (var value : params.getOrDefault("sort", List.of("createdAt,desc"))) {
            var parts = value.split(",", -1);
            if (parts.length > 2 || !SORT_FIELDS.contains(parts[0])) throw new com.rlibanez.eplsync.exception.UserInputException("Ordenación de descargas inválida");
            orders.add(new Sort.Order(parts.length == 1 ? Sort.Direction.ASC : Sort.Direction.fromString(parts[1]), parts[0].equals("fileExtensions") ? "extensionsSort" : Set.of("title","author").contains(parts[0]) ? "catalogBook."+parts[0] : parts[0]));
        }
        com.rlibanez.eplsync.config.QueryLimits.sort(Sort.by(orders));
        if (orders.stream().noneMatch(order -> order.getProperty().equals("id"))) orders.add(Sort.Order.asc("id"));
        var spec = filters(params);
        var result = repository.findAll(com.rlibanez.eplsync.ordering.TextOrdering.sorted(spec, Sort.by(orders)), PageRequest.of(page, size));
        var metadata = com.rlibanez.eplsync.torrent.DownloadBookMetadata.load(entityManager, result.getContent().stream().map((DownloadRecord entryValue) -> java.util.Objects.requireNonNull(entryValue).getEplId()).toList());
        loadExtensions(result.getContent());
        result.forEach(row -> {
            var book = metadata.get(row.getEplId());
            if (book != null) { row.setTitle(book.title()); row.setAuthor(book.author()); row.setCoverUrl(book.coverUrl()); row.setCoverAvailable(book.coverAvailable()); }
        });
        return new PageResponse<>(result.getContent(), new PageResponse.PageMeta(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast(), result.hasNext(), result.hasPrevious()));
    }

    void loadExtensions(List<DownloadRecord> records) {
        var extensions=new HashMap<String,List<String>>();
        if(!records.isEmpty()) for(var entry:entityManager.createQuery("select distinct f.downloadId,f.extension from DownloadFile f where f.downloadId in :ids order by f.extension",Object[].class).setParameter("ids",records.stream().map(value->java.util.Objects.requireNonNull(value).getId()).toList()).getResultList())
            extensions.computeIfAbsent((String)entry[0],ignored->new ArrayList<>()).add((String)entry[1]);
        records.forEach(row->row.setFileExtensions(List.copyOf(extensions.getOrDefault(row.getId(),List.of()))));
    }

    private void validate(MultiValueMap<String, String> params, boolean listing) {
        var allowed = new HashSet<>(Set.of("author","authorOperator","extensions","extensionsOperator","inventoryState","title", "titleOperator", "eplId", "eplIdOperator", "hash", "revision", "status", "statusOperator", "originOperator", "origin", "client", "clientInstanceId", "completed"));
        if (listing) allowed.addAll(Set.of("page", "size", "sort"));
        DATE_FIELDS.forEach(field -> { allowed.add(field + "From"); allowed.add(field + "To"); });
        if (!allowed.containsAll(params.keySet())) throw new com.rlibanez.eplsync.exception.UserInputException("Filtro de descargas desconocido");
        params.forEach((key, values) -> {
            if (!key.equals("sort") && values.size() != 1) throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro repetido: " + key);
            if (values.stream().anyMatch(value -> value == null || value.isBlank())) throw new com.rlibanez.eplsync.exception.UserInputException("Parámetro vacío: " + key);
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
                    case "authorOperator", "extensionsOperator", "titleOperator", "statusOperator", "originOperator", "eplIdOperator" -> com.rlibanez.eplsync.ordering.StateOperator.excluded(text);
                    case "extensions" -> {
                        var extensions=Arrays.stream(text.toLowerCase(Locale.ROOT).split(",",-1)).distinct().toList();
                        if(extensions.size()>50 || extensions.stream().anyMatch(v->!v.matches("[a-z0-9]{1,16}|unknown"))) throw new IllegalArgumentException();values.put(key,extensions);
                    }
                    case "inventoryState" -> {if(!Set.of("UNKNOWN","CHECKED","ERROR").contains(text)) throw new IllegalArgumentException();values.put(key,text);}
                    case "title", "author" -> { if(text.length()>256) throw new com.rlibanez.eplsync.exception.UserInputException(); if(!text.isBlank()) values.put(key,text.trim()); }
                    case "eplId" -> values.put(key, com.rlibanez.eplsync.filter.EplIds.delimited(com.rlibanez.eplsync.filter.EplIds.parse(text)));
                    case "revision" -> { double revision = Double.parseDouble(text); if (!Double.isFinite(revision) || revision < 0) throw new com.rlibanez.eplsync.exception.UserInputException(); values.put(key, revision); }
                    case "completed" -> { if (!text.equals("true") && !text.equals("false")) throw new com.rlibanez.eplsync.exception.UserInputException(); values.put(key, Boolean.valueOf(text)); }
                    case "status" -> values.put(key, Arrays.stream(text.split(",", -1)).map(DownloadStatus::valueOf).toList());
                    case "origin" -> values.put(key, Arrays.stream(text.split(",", -1)).map(DownloadRecord.Origin::valueOf).toList());
                    case "hash" -> { if (!text.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}")) throw new com.rlibanez.eplsync.exception.UserInputException(); values.put(key, text.toUpperCase(Locale.ROOT)); }
                    default -> values.put(key, key.endsWith("From") || key.endsWith("To") ? Instant.parse(text) : text);
                }
            } catch (RuntimeException ex) { throw new com.rlibanez.eplsync.exception.UserInputException("Valor inválido para " + key); }
        }
        DATE_FIELDS.forEach(field -> {
            var from = (Instant) values.get(field + "From"); var to = (Instant) values.get(field + "To");
            if (from != null && to != null && from.isAfter(to)) throw new com.rlibanez.eplsync.exception.UserInputException("Intervalo inválido para " + field);
        });
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            values.forEach((key, value) -> {
                if(key.equals("extensions")) {
                    var selected=(List<?>)value;
                    var sub=query.subquery(String.class);var file=sub.from(DownloadFile.class);
                    Predicate extension=file.get("extension").in(selected.stream().filter(v->!Set.of("unknown","error","other").contains(v)).toList());
                    if(selected.contains("other")) extension=cb.or(extension,cb.not(file.get("extension").in(MAIN_EXTENSIONS)));
                    sub.select(file.get("id")).where(cb.equal(file.get("downloadId"),root.get("id")),extension);
                    Predicate match=cb.exists(sub);
                    if(selected.contains("unknown")) match=cb.or(match,cb.and(cb.isNull(root.get("inventoryCheckedAt")),cb.notEqual(root.get("inventoryState"),"ERROR")));
                    if(selected.contains("error")) match=cb.or(match,cb.equal(root.get("inventoryState"),"ERROR"));
                    predicates.add("ne".equals(params.getFirst("extensionsOperator"))?cb.not(match):match);
                }
                else if(key.equals("inventoryState")) predicates.add(value.equals("UNKNOWN")?cb.isNull(root.get("inventoryCheckedAt")):cb.equal(root.get("inventoryState"),value));
                else if(key.equals("title") || key.equals("author")) {
                    var title=cb.coalesce(root.join("catalogBook",jakarta.persistence.criteria.JoinType.LEFT).<String>get(key),"");
                    var position=cb.locate(cb.lower(title),((String)value).toLowerCase(Locale.ROOT));
                    predicates.add("ne".equals(params.getFirst(key+"Operator"))?cb.equal(position,0):cb.greaterThan(position,0));
                }
                else if(key.equals("eplId")) {
                    var match=cb.gt(cb.locate(cb.literal((String)value),cb.concat(cb.concat(",",root.get("eplId").as(String.class)),",")),0);
                    predicates.add("ne".equals(params.getFirst("eplIdOperator"))?cb.not(match):match);
                }
                else if (key.equals("completed")) predicates.add((Boolean) value ? cb.isNotNull(root.get("completedAt")) : cb.isNull(root.get("completedAt")));
                else if (key.equals("status")) {
                    var predicate = root.get(key).in((List<?>) value);
                    predicates.add("ne".equals(params.getFirst("statusOperator")) ? cb.not(predicate) : predicate);
                }
                else if (key.equals("origin")) predicates.add("ne".equals(params.getFirst("originOperator"))?cb.not(root.get(key).in((List<?>) value)):root.get(key).in((List<?>) value));
                else if (key.endsWith("From")) predicates.add(cb.greaterThanOrEqualTo(root.get(key.substring(0, key.length() - 4)), (Instant) value));
                else if (key.endsWith("To")) predicates.add(cb.lessThanOrEqualTo(root.get(key.substring(0, key.length() - 2)), (Instant) value));
                else predicates.add(cb.equal(root.get(key), value));
            });
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    @Transactional(readOnly=true)
    public List<String> extensions() {return entityManager.createQuery("select distinct f.extension from DownloadFile f where f.extension<>'' order by f.extension",String.class).setMaxResults(1000).getResultList();}

    private int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new com.rlibanez.eplsync.exception.UserInputException("Paginación inválida"); }
    }
}
