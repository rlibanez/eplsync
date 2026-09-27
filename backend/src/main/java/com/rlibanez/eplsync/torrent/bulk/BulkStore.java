package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.torrent.TorrentDownload;
import jakarta.persistence.EntityManager;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class BulkStore {
    private final BulkJobRepository jobs;
    private final BulkItemRepository items;
    private final TorrentDownloadService downloads;
    private final TorrentClientService client;
    private final TorrentProperties properties;
    private final EntityManager em;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public BulkStore(BulkJobRepository jobs, BulkItemRepository items,
            TorrentDownloadService downloads, TorrentClientService client, TorrentProperties properties, EntityManager em) {
        this.jobs = jobs; this.items = items; this.downloads = downloads;
        this.client = client; this.properties = properties; this.em = em;
    }

    public record View(String jobId, BulkJob.State status, String client, long selectedBooks,
            long processedBooks, long accepted, long alreadyExists, long skipped, long failed,
            long pending, long inFlight, long cancelled, int batchSize, int concurrency,
            String interval, Instant createdAt, Instant updatedAt, Instant retryAt, String message,
            MultipleHashes multipleHashes, long selectedTorrents, long processedTorrents,
            long selectedItems, long processedItems) {}
    public record ItemView(String id, Long eplId, String hash, BulkItem.State status, int attempts, String message) {}

    @Transactional
    public View create(CatalogBookFilter filter, Pageable pageable, boolean paginated, boolean all, BulkRequest request) {
        client.requireEnabled();
        filter.normalize();
        if (!paginated && !all && !hasFilter(filter))
            throw new IllegalArgumentException("Sin filtros ni paginación se requiere all=true");
        var input = request == null ? new BulkRequest(null, null, null, null) : request;
        if (input.options() != null && input.options().hash() != null)
            throw new IllegalArgumentException("Bulk selecciona el hash de cada libro; no admite options.hash");
        int batchSize = input.batchSize() == null ? properties.getBulk().getBatchSize() : input.batchSize();
        int concurrency = input.concurrency() == null ? properties.getBulk().getConcurrency() : input.concurrency();
        var policy = input.multipleHashes() == null ? properties.getBulk().getMultipleHashes() : input.multipleHashes();
        if (policy == null) throw new IllegalArgumentException("multipleHashes debe ser all, skip o first");
        long interval;
        try {
            var duration = input.interval() == null ? properties.getBulk().getInterval()
                    : DurationStyle.detectAndParse(input.interval());
            if (duration.isNegative() || duration.compareTo(java.time.Duration.ofSeconds(60)) > 0)
                throw new IllegalArgumentException("interval fuera de rango");
            interval = duration.toMillis();
        } catch (RuntimeException ex) { throw new IllegalArgumentException("interval debe ser una duración como 500ms o 2s"); }
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize debe estar entre 1 y 1000");
        if (concurrency < 1 || concurrency > 16) throw new IllegalArgumentException("concurrency debe estar entre 1 y 16");
        if (interval < 0 || interval > 60_000) throw new IllegalArgumentException("interval debe estar entre 0ms y 60s");
        var job = new BulkJob();
        job.setId(UUID.randomUUID().toString()); job.setState(BulkJob.State.QUEUED);
        job.setMultipleHashes(policy);
        job.setClient(properties.getClient()); job.setTargetFingerprint(fingerprint());
        job.setBatchSize(batchSize); job.setConcurrency(concurrency); job.setIntervalMillis(interval);
        job.setCreatedAt(Instant.now()); job.setUpdatedAt(job.getCreatedAt());
        jobs.saveAndFlush(job);
        var sort = pageable.getSort();
        if (sort.getOrderFor("eplId") == null) sort = sort.and(Sort.by("eplId"));
        validateSort(sort);
        var spec = CatalogBookSpecifications.fromFilter(filter);
        long offset = paginated ? pageable.getOffset() : 0;
        long remaining = paginated ? pageable.getPageSize() : Long.MAX_VALUE;
        long position = 0;
        long selectedBooks = 0;
        var magnets = new com.rlibanez.eplsync.torrent.MagnetLinkBuilder(properties);
        var seen = new HashSet<String>();
        // Una única transacción mantiene consistente la selección; las entidades se liberan por lote.
        while (remaining > 0) {
            int size = (int) Math.min(batchSize, remaining);
            var criteria = em.getCriteriaBuilder();
            var query = criteria.createQuery(com.rlibanez.eplsync.model.CatalogBook.class);
            var root = query.from(com.rlibanez.eplsync.model.CatalogBook.class);
            query.select(root);
            var predicate = spec.toPredicate(root, query, criteria);
            if (predicate != null) query.where(predicate);
            var orders = new ArrayList<jakarta.persistence.criteria.Order>();
            for (var order : sort) orders.add(order.isAscending() ? criteria.asc(root.get(order.getProperty())) : criteria.desc(root.get(order.getProperty())));
            query.orderBy(orders);
            if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("Paginación fuera de rango");
            var batch = em.createQuery(query).setFirstResult((int) offset).setMaxResults(size).getResultList();
            if (batch.isEmpty()) break;
            for (var book : batch) {
                selectedBooks++;
                var hashes = magnets.hashes(book.getLinks());
                if (hashes.isEmpty() || (hashes.size() > 1 && policy == MultipleHashes.SKIP)) {
                    var item = newItem(job, book.getEplId(), position++);
                    item.setState(BulkItem.State.SKIPPED);
                    item.setMessage(hashes.isEmpty() ? "El libro no tiene hashes torrent válidos"
                            : "Libro omitido por multipleHashes=skip: tiene varios hashes");
                    items.save(item);
                    continue;
                }
                var selectedHashes = policy == MultipleHashes.ALL ? hashes : List.of(hashes.getFirst());
                for (String hash : selectedHashes) {
                    var item = newItem(job, book.getEplId(), position++);
                    item.setHash(hash);
                    if (!seen.add(hash)) {
                        item.setState(BulkItem.State.SKIPPED); item.setMessage("Hash duplicado en la selección");
                    } else {
                        try {
                            var options = input.options();
                            var explicit = options == null
                                    ? new com.rlibanez.eplsync.dto.TorrentDownloadRequest(hash, null, null, null, null)
                                    : new com.rlibanez.eplsync.dto.TorrentDownloadRequest(hash, options.start(), options.savePath(), options.rename(), options.qbittorrent());
                            var command = downloads.prepare(book, explicit);
                            item.setCommandJson(mapper.writeValueAsString(command));
                        } catch (TorrentOperationException | IllegalArgumentException ex) {
                            item.setState(BulkItem.State.SKIPPED); item.setMessage(safeMessage(ex));
                        }
                    }
                    items.save(item);
                }
            }
            offset += batch.size(); remaining -= batch.size();
            em.flush(); em.clear();
            if (batch.size() < size) break;
        }
        job.setSelectedBooks(selectedBooks);
        if (items.countByJobIdAndState(job.getId(), BulkItem.State.PENDING) == 0) job.setState(BulkJob.State.COMPLETED);
        jobs.saveAndFlush(job);
        return view(job.getId());
    }

    private BulkItem newItem(BulkJob job, Long eplId, long position) {
        var item = new BulkItem();
        item.setId(UUID.randomUUID().toString()); item.setJobId(job.getId());
        item.setPosition(position); item.setEplId(eplId); item.setState(BulkItem.State.PENDING);
        return item;
    }

    private boolean hasFilter(CatalogBookFilter filter) {
        var bean = new BeanWrapperImpl(filter);
        for (var descriptor : bean.getPropertyDescriptors()) {
            if (descriptor.getName().equals("class")) continue;
            var value = bean.getPropertyValue(descriptor.getName());
            if (value != null && (!(value instanceof Collection<?> c) || !c.isEmpty())) return true;
        }
        return false;
    }

    private void validateSort(Sort sort) {
        var bean = new BeanWrapperImpl(com.rlibanez.eplsync.model.CatalogBook.class);
        for (var order : sort) if (order.getProperty().equals("class") || order.getProperty().contains(".")
                || !bean.isReadableProperty(order.getProperty())) throw new IllegalArgumentException("Campo sort inválido");
    }

    @Transactional(readOnly = true)
    public View view(String id) {
        var j = job(id);
        long accepted = count(id, BulkItem.State.ACCEPTED), existing = count(id, BulkItem.State.ALREADY_EXISTS);
        long skipped = count(id, BulkItem.State.SKIPPED), failed = count(id, BulkItem.State.FAILED);
        return new View(id, j.getState(), j.getClient(), j.getSelectedBooks(), items.processedBooks(id, List.of(BulkItem.State.PENDING, BulkItem.State.IN_FLIGHT, BulkItem.State.CANCELLED)),
                accepted, existing, skipped, failed, count(id, BulkItem.State.PENDING), count(id, BulkItem.State.IN_FLIGHT),
                count(id, BulkItem.State.CANCELLED), j.getBatchSize(), j.getConcurrency(), j.getIntervalMillis() + "ms",
                j.getCreatedAt(), j.getUpdatedAt(), j.getRetryAt(), j.getMessage(),
                j.getMultipleHashes() == null ? MultipleHashes.SKIP : j.getMultipleHashes(),
                items.selectedTorrents(id), items.processedTorrents(id, List.of(BulkItem.State.PENDING, BulkItem.State.IN_FLIGHT, BulkItem.State.CANCELLED)),
                items.countByJobId(id), accepted + existing + skipped + failed);
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemView> details(String id, int page, int size) {
        job(id);
        if (page < 0 || size < 1 || size > 500) throw new IllegalArgumentException("page >= 0 y size entre 1 y 500");
        var result = items.findByJobIdOrderByPosition(id, PageRequest.of(page, size));
        return new PageResponse<>(result.map(i -> new ItemView(i.getId(), i.getEplId(), i.getHash(), i.getState(), i.getAttempts(), i.getMessage())).getContent(),
                new PageResponse.PageMeta(page, size, result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast(), result.hasNext(), result.hasPrevious()));
    }

    @Transactional
    public View control(String id, String action) {
        var job = job(id);
        if (job.getState() == BulkJob.State.COMPLETED || job.getState() == BulkJob.State.CANCELLED)
            throw new TorrentOperationException(HttpStatus.CONFLICT, "El trabajo ya ha finalizado");
        switch (action) {
            case "pause" -> job.setState(BulkJob.State.PAUSED);
            case "resume" -> {
                client.requireEnabled();
                if (!job.getTargetFingerprint().equals(fingerprint())) throw new TorrentOperationException(HttpStatus.CONFLICT,
                        "El cliente o su URL han cambiado; restaura el destino original");
                if (job.getState() != BulkJob.State.PAUSED && job.getState() != BulkJob.State.RETRY_WAIT)
                    throw new TorrentOperationException(HttpStatus.CONFLICT, "El trabajo no está pausado");
                job.setState(BulkJob.State.QUEUED); job.setRetryAt(null); job.setMessage(null);
            }
            case "cancel" -> {
                job.setState(BulkJob.State.CANCELLED);
                items.transition(id, BulkItem.State.PENDING, BulkItem.State.CANCELLED);
            }
            default -> throw new IllegalArgumentException("Acción desconocida");
        }
        job.setUpdatedAt(Instant.now()); jobs.saveAndFlush(job);
        return view(id);
    }

    @Transactional
    public void recover() {
        items.recover(BulkItem.State.IN_FLIGHT, BulkItem.State.PENDING);
        for (var state : List.of(BulkJob.State.RUNNING)) {
            for (var job : jobs.findByStateOrderByCreatedAtAsc(state, Pageable.unpaged())) {
                job.setState(BulkJob.State.QUEUED); job.setMessage("Recuperado tras reinicio; se comprobarán hashes antes de enviar");
            }
        }
        // Los elementos en vuelo de trabajos cancelados tampoco se vuelven a enviar.
        for (var job : jobs.findByStateOrderByCreatedAtAsc(BulkJob.State.CANCELLED, Pageable.unpaged()))
            items.transition(job.getId(), BulkItem.State.PENDING, BulkItem.State.CANCELLED);
    }

    @Transactional
    public BulkJob next() {
        var now = Instant.now();
        for (var job : jobs.findByStateOrderByCreatedAtAsc(BulkJob.State.RETRY_WAIT, Pageable.unpaged()))
            if (job.getRetryAt() != null && !job.getRetryAt().isAfter(now)) job.setState(BulkJob.State.QUEUED);
        var candidates = jobs.findByStateOrderByCreatedAtAsc(BulkJob.State.RUNNING, PageRequest.of(0, 1));
        if (candidates.isEmpty()) candidates = jobs.findByStateOrderByCreatedAtAsc(BulkJob.State.QUEUED, PageRequest.of(0, 1));
        if (candidates.isEmpty()) return null;
        var job = candidates.getFirst();
        if (!job.getTargetFingerprint().equals(fingerprint())) {
            job.setState(BulkJob.State.PAUSED); job.setMessage("El destino configurado ha cambiado"); return null;
        }
        job.setState(BulkJob.State.RUNNING); return job;
    }

    @Transactional(readOnly = true)
    public List<String> pending(String id, int limit) {
        return items.findByJobIdAndStateOrderByPosition(id, BulkItem.State.PENDING, PageRequest.of(0, limit))
                .stream().map(BulkItem::getId).toList();
    }

    @Transactional
    public BulkItem claim(String jobId, String id) {
        if (job(jobId).getState() != BulkJob.State.RUNNING) return null;
        var item = items.findById(id).orElseThrow();
        if (item.getState() != BulkItem.State.PENDING) return null;
        item.setState(BulkItem.State.IN_FLIGHT); item.setAttempts(item.getAttempts() + 1);
        items.saveAndFlush(item); return item;
    }

    public TorrentDownload command(BulkItem item) { return mapper.readValue(item.getCommandJson(), TorrentDownload.class); }

    @Transactional
    public void finish(String id, BulkItem.State result, String message, boolean pause, boolean retry) {
        var item = items.findById(id).orElseThrow();
        var job = job(item.getJobId());
        if (pause || retry) {
            item.setState(job.getState() == BulkJob.State.CANCELLED ? BulkItem.State.CANCELLED : BulkItem.State.PENDING);
            if (job.getState() != BulkJob.State.CANCELLED && job.getState() != BulkJob.State.PAUSED) {
                job.setState(retry && item.getAttempts() <= 3 ? BulkJob.State.RETRY_WAIT : BulkJob.State.PAUSED);
                job.setRetryAt(job.getState() == BulkJob.State.RETRY_WAIT ? Instant.now().plusSeconds(30L << (Math.min(item.getAttempts(), 3) - 1)) : null);
                job.setMessage(message);
            }
        } else item.setState(result);
        item.setMessage(message); job.setUpdatedAt(Instant.now());
        items.saveAndFlush(item);
        complete(job);
    }

    @Transactional
    public void completeIfEmpty(String id) { complete(job(id)); }
    private void complete(BulkJob job) {
        if (job.getState() == BulkJob.State.RUNNING && count(job.getId(), BulkItem.State.PENDING) == 0
                && count(job.getId(), BulkItem.State.IN_FLIGHT) == 0) {
            job.setState(BulkJob.State.COMPLETED); job.setUpdatedAt(Instant.now()); job.setMessage(null); job.setRetryAt(null);
        }
    }
    public BulkJob job(String id) { return jobs.findById(id).orElseThrow(() -> new TorrentOperationException(HttpStatus.NOT_FOUND, "Trabajo torrent inexistente")); }
    private long count(String id, BulkItem.State state) { return items.countByJobIdAndState(id, state); }
    private String fingerprint() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (properties.getClient() + "\n" + properties.getBaseUrl().replaceAll("/+$", "")).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? "Opciones inválidas" : message.substring(0, Math.min(500, message.length()));
    }
}
