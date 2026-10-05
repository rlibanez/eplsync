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
    @org.springframework.beans.factory.annotation.Autowired private com.rlibanez.eplsync.events.EventJournal events;
    private void event(BulkJob job, com.rlibanez.eplsync.events.EventJournal.Outcome outcome) {
        if (events == null) return;
        var summary = view(job);
        var origin = job.getEventOrigin() == null ? com.rlibanez.eplsync.events.EventContext.Origin.MANUAL
            : com.rlibanez.eplsync.events.EventContext.Origin.valueOf(job.getEventOrigin());
        events.record(com.rlibanez.eplsync.events.EventJournal.Category.JOB, "DOWNLOAD", outcome, origin, job.getId(),
            Map.of("selected", summary.selectedItems(), "processed", summary.processedItems(), "accepted", summary.accepted(),
                "alreadyExists", summary.alreadyExists(), "failed", summary.failed(), "skipped", summary.skipped()));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkStore.class);

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

    @Transactional(timeout=120)
    public View create(CatalogBookFilter filter, Pageable pageable, boolean paginated, boolean all, BulkRequest request) {
        return (View) prepare(filter,pageable,paginated,all,request,false,false,0,20);
    }
    public record Preview(boolean dryRun, boolean applied, long selectedBooks, long selectedItems,
                          long skipped, List<ItemView> items, PageResponse.PageMeta meta) {
        public Preview(boolean dryRun,boolean applied,long books,long count,long skipped,List<ItemView> items) {
            this(dryRun,applied,books,count,skipped,items,new PageResponse.PageMeta(0,20,count,(int)((count+19)/20),true,count<=20,count>20,false));
        }
    }
    @Transactional(readOnly=true,timeout=120)
    public Preview preview(CatalogBookFilter filter, Pageable pageable, boolean paginated, boolean all,
                           BulkRequest request, boolean includeDetails) {
        return preview(filter,pageable,paginated,all,request,includeDetails,0,20);
    }
    @Transactional(readOnly=true,timeout=120)
    public Preview preview(CatalogBookFilter filter,Pageable pageable,boolean paginated,boolean all,
            BulkRequest request,boolean includeDetails,int detailPage,int detailSize) {
        com.rlibanez.eplsync.config.QueryLimits.page(detailPage,detailSize);
        return (Preview) prepare(filter,pageable,paginated,all,request,true,includeDetails,detailPage,detailSize);
    }
    private Object prepare(CatalogBookFilter filter, Pageable pageable, boolean paginated, boolean all,
                           BulkRequest request, boolean dryRun, boolean includeDetails,int detailPage,int detailSize) {
        var details = new ArrayList<ItemView>();
        long skipped = 0;
        client.requireEnabled();
        filter.normalize();
        if (!paginated && !all && !hasFilter(filter))
            throw new IllegalArgumentException("Sin filtros ni paginación se requiere all=true");
        var input = request == null ? new BulkRequest(null, null, null, null) : request;
        var job = newJob(input, !dryRun);
        var policy = job.getMultipleHashes();
        var sort = pageable.getSort();
        if (sort.getOrderFor("eplId") == null) sort = sort.and(Sort.by("eplId"));
        validateSort(sort);
        var spec = CatalogBookSpecifications.fromFilter(filter);
        long offset = paginated ? pageable.getOffset() : 0;
        long remaining = paginated ? pageable.getPageSize() : Long.MAX_VALUE;
        var countCriteria = em.getCriteriaBuilder();
        var countQuery = countCriteria.createQuery(Long.class);
        var countRoot = countQuery.from(com.rlibanez.eplsync.model.CatalogBook.class);
        countQuery.select(countCriteria.count(countRoot));
        var countPredicate = spec.toPredicate(countRoot, countQuery, countCriteria);
        if (countPredicate != null) countQuery.where(countPredicate);
        long matchingBooks = em.createQuery(countQuery).getSingleResult();
        long totalBooks = paginated
            ? Math.max(0, Math.min(matchingBooks - offset, pageable.getPageSize()))
            : matchingBooks;
        if (!dryRun && totalBooks == 0) throw new IllegalArgumentException("No hay libros que coincidan con los filtros");
        long position = 0;
        long selectedBooks = 0;
        int lastProgressCheckpoint = 0;
        var magnets = new com.rlibanez.eplsync.torrent.MagnetLinkBuilder(properties);
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
        try(var seen=new SelectionHashIndex()) {
        // Una única transacción mantiene consistente la selección; las entidades se liberan por lote.
        while (remaining > 0) {
            if(System.nanoTime()>=deadline || Thread.currentThread().isInterrupted())
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,
                        "La selección de torrents ha superado el tiempo máximo de 2 minutos. Reduce los filtros e inténtalo de nuevo.");
            int size = (int) Math.min(100, remaining);
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
                    if (!dryRun) items.save(item);
                    if (item.getState() == BulkItem.State.SKIPPED) skipped++;
                    if (dryRun && includeDetails && item.getPosition()>=(long)detailPage*detailSize && item.getPosition()<((long)detailPage+1)*detailSize) details.add(new ItemView(null,item.getEplId(),item.getHash(),item.getState(),0,item.getMessage()));
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
                    if (!dryRun) items.save(item);
                    if (item.getState() == BulkItem.State.SKIPPED) skipped++;
                    if (dryRun && includeDetails && item.getPosition()>=(long)detailPage*detailSize && item.getPosition()<((long)detailPage+1)*detailSize) details.add(new ItemView(null,item.getEplId(),item.getHash(),item.getState(),0,item.getMessage()));
                }
            }
            offset += batch.size(); remaining -= batch.size();
            if (!dryRun) { em.flush(); em.clear(); }
            else em.clear();
            if (totalBooks > 0) {
                int progressPercent = (int) (selectedBooks * 100 / totalBooks);
                int checkpointPercent = progressPercent / 10 * 10;
                if (checkpointPercent >= 10 && checkpointPercent > lastProgressCheckpoint) {
                    log.info("Preparación bulk en progreso: dryRun={}, jobId={}, progreso={}%, librosProcesados={}/{}, itemsPreparados={}",
                            dryRun, job.getId(), progressPercent, selectedBooks, totalBooks, position);
                    lastProgressCheckpoint = checkpointPercent;
                }
            }
            if (batch.size() < size) break;
        }
        }
        if (dryRun) {
            int pages=(int)((position+detailSize-1)/detailSize);
            return new Preview(true,false,selectedBooks,position,skipped,details,
                    new PageResponse.PageMeta(detailPage,detailSize,position,pages,detailPage==0,detailPage>=pages-1,detailPage<pages-1,detailPage>0));
        }
        job.setSelectedBooks(selectedBooks);
        if (items.countByJobIdAndState(job.getId(), BulkItem.State.PENDING) == 0) job.setState(BulkJob.State.COMPLETED);
        jobs.saveAndFlush(job);
        event(job, com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED);
        if (job.getState() == BulkJob.State.COMPLETED) event(job, com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        return view(job.getId());
    }

    private BulkJob newJob(BulkRequest input) { return newJob(input, true); }
    private BulkJob newJob(BulkRequest input, boolean persist) {
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
        job.setEventOrigin(com.rlibanez.eplsync.events.EventContext.origin().name());
        job.setId(UUID.randomUUID().toString()); job.setState(BulkJob.State.QUEUED);
        job.setMultipleHashes(policy);
        job.setClient(properties.getClient()); job.setTargetFingerprint(fingerprint());
        job.setBatchSize(batchSize); job.setConcurrency(concurrency); job.setIntervalMillis(interval);
        job.setCreatedAt(Instant.now()); job.setUpdatedAt(job.getCreatedAt());
        if (persist) jobs.saveAndFlush(job);
        return job;
    }

    /** Prepared commands are appended individually within the caller's transaction. */
    @Transactional public BulkJob beginPrepared(BulkRequest input) { client.requireEnabled(); return newJob(input); }
    @Transactional public void appendPrepared(String id,TorrentDownload command,long position) {
        var item=new BulkItem(); item.setId(UUID.randomUUID().toString()); item.setJobId(id);
        item.setEplId(command.book().getEplId()); item.setPosition(position); item.setState(BulkItem.State.PENDING);
        item.setHash(command.hash()); item.setCommandJson(mapper.writeValueAsString(command)); items.save(item);
    }
    @Transactional public View finishPrepared(String id,long selectedBooks) {
        em.flush(); var job=job(id); job.setSelectedBooks(selectedBooks);
        if(items.countByJobId(id)==0) job.setState(BulkJob.State.COMPLETED);
        jobs.saveAndFlush(job); event(job,com.rlibanez.eplsync.events.EventJournal.Outcome.STARTED);
        if(job.getState()==BulkJob.State.COMPLETED) event(job,com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
        return view(id);
    }
    @Transactional public View createPrepared(List<TorrentDownload> commands,BulkRequest input) {
        var job=beginPrepared(input); long position=0;
        for(var command:commands) {
            appendPrepared(job.getId(),command,position++);
            if(position%25==0) { em.flush(); em.clear(); }
        }
        em.flush();
        long count=em.createQuery("select count(distinct i.eplId) from BulkItem i where i.jobId=:id",Long.class)
                .setParameter("id",job.getId()).getSingleResult();
        return finishPrepared(job.getId(),count);
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
            if (value != null && (!(value instanceof Collection<?> c) || !c.isEmpty())
                    && (!value.getClass().isArray() || java.lang.reflect.Array.getLength(value) > 0)) return true;
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
        return view(job(id));
    }

    private View view(BulkJob j) {
        String id = j.getId();
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
    public PageResponse<View> list(int page, int size, List<BulkJob.State> states) {
        com.rlibanez.eplsync.config.QueryLimits.page(page, size);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = states == null ? jobs.findAll(pageable) : jobs.findByStateIn(states, pageable);
        return new PageResponse<>(result.getContent().stream().map(this::view).toList(),
                new PageResponse.PageMeta(page, size, result.getTotalElements(), result.getTotalPages(),
                        result.isFirst(), result.isLast(), result.hasNext(), result.hasPrevious()));
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemView> details(String id, int page, int size) {
        return details(id, page, size, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemView> details(String id, int page, int size, List<BulkItem.State> states) {
        job(id);
        com.rlibanez.eplsync.config.QueryLimits.page(page, size);
        var result = states == null ? items.findByJobIdOrderByPosition(id, PageRequest.of(page, size))
                : items.findByJobIdAndStateInOrderByPosition(id, states, PageRequest.of(page, size));
        return new PageResponse<>(result.map(i -> new ItemView(i.getId(), i.getEplId(), i.getHash(), i.getState(), i.getAttempts(), i.getMessage())).getContent(),
                new PageResponse.PageMeta(page, size, result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast(), result.hasNext(), result.hasPrevious()));
    }

    @Transactional
    public View control(String id, String action) {
        var job = job(id);
        if (job.getState() == BulkJob.State.COMPLETED || job.getState() == BulkJob.State.CANCELLED)
            throw new TorrentOperationException(HttpStatus.CONFLICT, "El trabajo ya ha finalizado");
        var previousState = job.getState();
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
        if (previousState != job.getState()) event(job, switch (action) {
            case "pause" -> com.rlibanez.eplsync.events.EventJournal.Outcome.PAUSED;
            case "resume" -> com.rlibanez.eplsync.events.EventJournal.Outcome.RESUMED;
            default -> com.rlibanez.eplsync.events.EventJournal.Outcome.CANCELLED;
        });
        return view(id);
    }

    @Transactional
    public void recover() {
        items.recover(BulkItem.State.IN_FLIGHT, BulkItem.State.PENDING);
        for (var state : List.of(BulkJob.State.RUNNING)) {
            for (var job : jobs.findByStateOrderByCreatedAtAsc(state, Pageable.unpaged())) {
                job.setState(BulkJob.State.QUEUED); job.setMessage("Recuperado tras reinicio; se comprobarán hashes antes de enviar");
                event(job, com.rlibanez.eplsync.events.EventJournal.Outcome.RECOVERED);
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
            if (job.getRetryAt() != null && !job.getRetryAt().isAfter(now)) {
                job.setState(BulkJob.State.QUEUED);
                event(job, com.rlibanez.eplsync.events.EventJournal.Outcome.RESUMED);
            }
        var candidates = jobs.findByStateOrderByCreatedAtAsc(BulkJob.State.RUNNING, PageRequest.of(0, 1));
        if (candidates.isEmpty()) candidates = jobs.findByStateOrderByCreatedAtAsc(BulkJob.State.QUEUED, PageRequest.of(0, 1));
        if (candidates.isEmpty()) return null;
        var job = candidates.getFirst();
        if (!job.getTargetFingerprint().equals(fingerprint())) {
            job.setState(BulkJob.State.PAUSED); job.setMessage("El destino configurado ha cambiado");
            event(job, com.rlibanez.eplsync.events.EventJournal.Outcome.PAUSED); return null;
        }
        job.setState(BulkJob.State.RUNNING); return job;
    }

    @Transactional(readOnly = true)
    public List<String> pending(String id, int limit) {
        return items.findByJobIdAndStateOrderByPosition(id, BulkItem.State.PENDING, PageRequest.of(0, limit))
                .stream().map(value -> Objects.requireNonNull(value).getId()).toList();
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
                event(job, job.getState() == BulkJob.State.RETRY_WAIT
                    ? com.rlibanez.eplsync.events.EventJournal.Outcome.RETRY_WAIT
                    : com.rlibanez.eplsync.events.EventJournal.Outcome.PAUSED);
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
            event(job, count(job.getId(), BulkItem.State.FAILED) > 0
                ? com.rlibanez.eplsync.events.EventJournal.Outcome.PARTIAL : com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED);
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
