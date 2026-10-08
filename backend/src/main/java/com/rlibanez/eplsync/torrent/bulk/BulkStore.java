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
        var currentActor = com.rlibanez.eplsync.events.EventContext.actor();
        var actor = "USER".equals(currentActor.kind()) ? currentActor : job.eventActor();
        var details = new java.util.LinkedHashMap<String,Object>();
        details.putAll(Map.of("selected", summary.selectedItems(), "processed", summary.processedItems(), "accepted", summary.accepted(),
                "alreadyExists", summary.alreadyExists(), "failed", summary.failed(), "skipped", summary.skipped()));
        if (job.getMessage() != null) details.put("reason", job.getMessage());
        events.recordAs(actor, com.rlibanez.eplsync.events.EventJournal.Category.JOB, "DOWNLOAD", outcome, origin, job.getId(), details);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkStore.class);

    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationEventPublisher publisher;
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
            long selectedItems, long processedItems, BulkJob.Type type, com.rlibanez.eplsync.torrent.updates.PreviousVersions previousVersions, CleanupSummary cleanup, com.rlibanez.eplsync.torrent.updates.CleanupTiming cleanupTiming) {}
    public record CleanupSummary(long waiting,long blocked,long requested,long removed,long cancelled) {}
    public record ItemView(String id, Long eplId, String hash, BulkItem.State status, int attempts, String message, String title, String coverUrl, Boolean coverAvailable, Double revision) {
        public ItemView(String id, Long eplId, String hash, BulkItem.State status, int attempts, String message) { this(id,eplId,hash,status,attempts,message,null,null,false,null); }
    }

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
            throw new com.rlibanez.eplsync.exception.UserInputException("Sin filtros ni paginación se requiere all=true");
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
        if (!dryRun && totalBooks == 0) throw new com.rlibanez.eplsync.exception.UserInputException("No hay libros que coincidan con los filtros");
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
            if (offset > Integer.MAX_VALUE) throw new com.rlibanez.eplsync.exception.UserInputException("Paginación fuera de rango");
            var batch = em.createQuery(query).setFirstResult((int) offset).setMaxResults(size).getResultList();
            if (batch.isEmpty()) break;
            for (var book : batch) {
                selectedBooks++;
                var hashes = magnets.hashes(book.getLinks());
                if (hashes.isEmpty() || (hashes.size() > 1 && policy == MultipleHashes.SKIP)) {
                    var item = newItem(job, book.getEplId(), book.getRevision(), position++);
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
                    var item = newItem(job, book.getEplId(), book.getRevision(), position++);
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
            throw new com.rlibanez.eplsync.exception.UserInputException("Bulk selecciona el hash de cada libro; no admite options.hash");
        int batchSize = input.batchSize() == null ? properties.getBulk().getBatchSize() : input.batchSize();
        int concurrency = input.concurrency() == null ? properties.getBulk().getConcurrency() : input.concurrency();
        var policy = input.multipleHashes() == null ? properties.getBulk().getMultipleHashes() : input.multipleHashes();
        if (policy == null) throw new com.rlibanez.eplsync.exception.UserInputException("multipleHashes debe ser all, skip o first");
        long interval;
        try {
            var duration = input.interval() == null ? properties.getBulk().getInterval()
                    : DurationStyle.detectAndParse(input.interval());
            if (duration.isNegative() || duration.compareTo(java.time.Duration.ofSeconds(60)) > 0)
                throw new com.rlibanez.eplsync.exception.UserInputException("interval fuera de rango");
            interval = duration.toMillis();
        } catch (RuntimeException ex) { throw new com.rlibanez.eplsync.exception.UserInputException("interval debe ser una duración como 500ms o 2s"); }
        if (batchSize < 1 || batchSize > 1000) throw new com.rlibanez.eplsync.exception.UserInputException("batchSize debe estar entre 1 y 1000");
        if (concurrency < 1 || concurrency > 16) throw new com.rlibanez.eplsync.exception.UserInputException("concurrency debe estar entre 1 y 16");
        if (interval < 0 || interval > 60_000) throw new com.rlibanez.eplsync.exception.UserInputException("interval debe estar entre 0ms y 60s");
        var job = new BulkJob();
        job.setEventOrigin(com.rlibanez.eplsync.events.EventContext.origin().name());
        var actor = com.rlibanez.eplsync.events.EventContext.actor();
        job.setEventActorId(actor.id()); job.setEventActorUsername(actor.username()); job.setEventActorKind(actor.kind());
        job.setId(UUID.randomUUID().toString()); job.setState(BulkJob.State.QUEUED);
        job.setMultipleHashes(policy);
        job.setClient(properties.getClient()); job.setTargetFingerprint(fingerprint());
        job.setBatchSize(batchSize); job.setConcurrency(concurrency); job.setIntervalMillis(interval);
        job.setCreatedAt(Instant.now()); job.setUpdatedAt(job.getCreatedAt());
        if (persist) return jobs.saveAndFlush(job);
        return job;
    }

    /** Prepared commands are appended individually within the caller's transaction. */
    @Transactional public BulkJob beginPrepared(BulkRequest input) { client.requireEnabled(); return newJob(input); }
    @Transactional public void appendPrepared(String id,TorrentDownload command,long position) {
        var item=new BulkItem(); item.setId(UUID.randomUUID().toString()); item.setJobId(id);
        item.setEplId(command.book().getEplId()); item.setRevision(command.book().getRevision()); item.setPosition(position); item.setState(BulkItem.State.PENDING);
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

    private BulkItem newItem(BulkJob job, Long eplId, Double revision, long position) {
        var item = new BulkItem();
        item.setId(UUID.randomUUID().toString()); item.setJobId(job.getId());
        item.setPosition(position); item.setEplId(eplId); item.setRevision(revision); item.setState(BulkItem.State.PENDING);
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
                || !bean.isReadableProperty(order.getProperty())) throw new com.rlibanez.eplsync.exception.UserInputException("Campo sort inválido");
    }

    @Transactional(readOnly = true)
    public View view(String id) {
        return view(job(id));
    }

    private View view(BulkJob j) {
        String id = j.getId();
        long accepted = count(id, BulkItem.State.ACCEPTED), existing = count(id, BulkItem.State.ALREADY_EXISTS);
        long skipped = count(id, BulkItem.State.SKIPPED), failed = count(id, BulkItem.State.FAILED);
        var plan=em.find(com.rlibanez.eplsync.torrent.updates.UpdatePlan.class,id);
        var counts=new java.util.EnumMap<com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State,Long>(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.class);
        if (plan!=null) for (var row:em.createQuery("select e.state,count(e) from UpdateCleanup e where e.jobId=:job group by e.state",Object[].class).setParameter("job",id).getResultList())
            counts.put((com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State)row[0],(Long)row[1]);
        var summary=plan==null ? null : new CleanupSummary(counts.getOrDefault(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.WAITING,0L),
            counts.getOrDefault(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.BLOCKED,0L),counts.getOrDefault(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REQUESTED,0L),
            counts.getOrDefault(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.REMOVED,0L),counts.getOrDefault(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.CANCELLED,0L));
        return new View(id, j.getState(), j.getClient(), j.getSelectedBooks(), items.processedBooks(id, List.of(BulkItem.State.PENDING, BulkItem.State.IN_FLIGHT, BulkItem.State.CANCELLED)),
                accepted, existing, skipped, failed, count(id, BulkItem.State.PENDING), count(id, BulkItem.State.IN_FLIGHT),
                count(id, BulkItem.State.CANCELLED), j.getBatchSize(), j.getConcurrency(), j.getIntervalMillis() + "ms",
                j.getCreatedAt(), j.getUpdatedAt(), j.getRetryAt(), j.getMessage(),
                j.getMultipleHashes() == null ? MultipleHashes.SKIP : j.getMultipleHashes(),
                items.selectedTorrents(id), items.processedTorrents(id, List.of(BulkItem.State.PENDING, BulkItem.State.IN_FLIGHT, BulkItem.State.CANCELLED)),
                items.countByJobId(id), accepted + existing + skipped + failed, plan==null ? BulkJob.Type.DOWNLOAD : BulkJob.Type.UPDATE,
                plan==null ? null : plan.getPreviousVersions(),summary, plan==null ? null : plan.getCleanupTiming()==null ? com.rlibanez.eplsync.torrent.updates.CleanupTiming.AFTER_DOWNLOAD : plan.getCleanupTiming());
    }

    @Transactional(readOnly = true)
    public PageResponse<View> list(int page, int size, List<BulkJob.State> states) {
        return list(page,size,states,"createdAt,desc");
    }
    @Transactional(readOnly = true)
    public PageResponse<View> list(int page, int size, List<BulkJob.State> states, String sort) {
        com.rlibanez.eplsync.config.QueryLimits.page(page, size);
        var fields=Map.of("jobId","id","status","state","type","type","selectedBooks","selectedBooks","createdAt","createdAt");
        var allowed = new HashSet<>(fields.keySet()); allowed.addAll(Set.of("progress", "accepted", "failed"));
        var criteria = com.rlibanez.eplsync.config.TableOrdering.parse(sort, allowed);
        org.springframework.data.domain.Page<BulkJob> result;
        if (criteria.stream().allMatch(order -> fields.containsKey(order.getProperty()))) {
            var orders = new ArrayList<Sort.Order>();
            criteria.forEach(order -> orders.add(new Sort.Order(order.getDirection(), fields.get(order.getProperty()))));
            if (orders.stream().noneMatch(order -> order.getProperty().equals("createdAt"))) orders.add(Sort.Order.desc("createdAt"));
            if (orders.stream().noneMatch(order -> order.getProperty().equals("id"))) orders.add(Sort.Order.desc("id"));
            var pageable = PageRequest.of(page, size, Sort.by(orders));
            result = states == null ? jobs.findAll(pageable) : jobs.findByStateIn(states, pageable);
        } else {
            // Aggregate and order in SQLite before pagination, including secondary criteria.
            String processed = "SUM(CASE WHEN i.state IN ('ACCEPTED','ALREADY_EXISTS','SKIPPED','FAILED') THEN 1 ELSE 0 END)";
            var expressions = Map.of("jobId", "j.id", "status", "j.state", "type", "j.type", "selectedBooks", "j.selected_books", "createdAt", "j.created_at",
                "progress", "CASE WHEN COUNT(i.id)=0 THEN 0 ELSE 1.0*"+processed+"/COUNT(i.id) END",
                "accepted", "SUM(CASE WHEN i.state='ACCEPTED' THEN 1 ELSE 0 END)", "failed", "SUM(CASE WHEN i.state='FAILED' THEN 1 ELSE 0 END)");
            String orderBy = criteria.stream().map(order -> expressions.get(order.getProperty())+" "+order.getDirection().name()).collect(java.util.stream.Collectors.joining(","));
            if (criteria.stream().noneMatch(order -> order.getProperty().equals("createdAt"))) orderBy += ",j.created_at DESC";
            if (criteria.stream().noneMatch(order -> order.getProperty().equals("jobId"))) orderBy += ",j.id DESC";
            String where = states == null ? "" : " WHERE j.state IN ("+String.join(",",Collections.nCopies(states.size(),"?"))+")";
            var query = em.createNativeQuery("SELECT j.id FROM torrent_bulk_jobs j LEFT JOIN torrent_bulk_items i ON i.job_id=j.id"+where+" GROUP BY j.id ORDER BY "+orderBy);
            if (states != null) for (int index=0;index<states.size();index++) query.setParameter(index+1,states.get(index).name());
            query.setFirstResult(Math.toIntExact((long)page*size)); query.setMaxResults(size);
            List<?> ids = query.getResultList();
            var records = new HashMap<String,BulkJob>();
            jobs.findAllById(ids.stream().map((Object entryValue) -> java.util.Objects.requireNonNull(entryValue).toString()).toList()).forEach(job -> records.put(job.getId(),job));
            var selected = ids.stream().map(id -> records.get(id.toString())).toList();
            result = new org.springframework.data.domain.PageImpl<>(selected,PageRequest.of(page,size),states==null ? jobs.count() : jobs.countByStateIn(states));
        }
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
        return details(id, page, size, states, "position,asc");
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemView> details(String id, int page, int size, List<BulkItem.State> states, String sort) {
        job(id);
        com.rlibanez.eplsync.config.QueryLimits.page(page, size);
        var criteria = com.rlibanez.eplsync.config.TableOrdering.parse(sort, Set.of("position", "title", "eplId", "revision", "hash", "status", "attempts", "message"));
        var orders = criteria.stream().map(order -> new Sort.Order(order.getDirection(), order.getProperty().equals("title") ? "catalogBook.title" : order.getProperty().equals("status") ? "state" : order.getProperty())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (orders.stream().noneMatch(order -> order.getProperty().equals("position"))) orders.add(Sort.Order.asc("position"));
        var pageable = PageRequest.of(page, size, Sort.by(orders));
        var result = states == null ? items.findByJobId(id, pageable)
                : items.findByJobIdAndStateIn(id, states, pageable);
        var metadata = com.rlibanez.eplsync.torrent.DownloadBookMetadata.load(em, result.getContent().stream().map((BulkItem entryValue) -> java.util.Objects.requireNonNull(entryValue).getEplId()).toList());
        result.forEach(row -> {
            var book = metadata.get(row.getEplId());
            if (book != null) { row.setTitle(book.title()); row.setCoverUrl(book.coverUrl()); row.setCoverAvailable(book.coverAvailable()); }
        });
        return new PageResponse<>(result.map(i -> new ItemView(i.getId(), i.getEplId(), i.getHash(), i.getState(), i.getAttempts(), i.getMessage(), i.getTitle(), i.getCoverUrl(), i.getCoverAvailable(), i.getRevision())).getContent(),
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
                em.createQuery("update UpdateCleanup e set e.state=:cancelled,e.updatedAt=:now where e.jobId=:job and e.state in :pending")
                    .setParameter("cancelled",com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.CANCELLED).setParameter("now",Instant.now())
                    .setParameter("job",id).setParameter("pending",List.of(com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.WAITING,com.rlibanez.eplsync.torrent.updates.UpdateCleanup.State.BLOCKED)).executeUpdate();
            }
            default -> throw new com.rlibanez.eplsync.exception.UserInputException("Acción desconocida");
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
        if (!checkDispatchConfiguration(jobId)) return null;
        var item = items.findById(id).orElseThrow();
        if (item.getState() != BulkItem.State.PENDING) return null;
        item.setState(BulkItem.State.IN_FLIGHT); item.setAttempts(item.getAttempts() + 1);
        items.saveAndFlush(item); return item;
    }

    /** Only prevents new claims; already dispatched requests retain their original snapshot. */
    @Transactional
    public boolean checkDispatchConfiguration(String id) {
        var job = job(id);
        if (job.getState() != BulkJob.State.RUNNING) return false;
        String reason = !properties.isEnabled() ? "La integración torrent se ha desactivado"
                : !job.getTargetFingerprint().equals(fingerprint()) ? "El destino configurado ha cambiado" : null;
        if (reason == null) return true;
        // If everything is already in flight, let its normal completion finalize the job.
        if (count(id, BulkItem.State.PENDING) == 0) return true;
        job.setState(BulkJob.State.PAUSED); job.setMessage(reason); job.setRetryAt(null);
        job.setUpdatedAt(Instant.now()); jobs.saveAndFlush(job);
        event(job, com.rlibanez.eplsync.events.EventJournal.Outcome.PAUSED);
        log.info("Trabajo bulk pausado: jobId={}, motivo={}; los envíos en curso terminarán",id,reason);
        return false;
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
        if (job.getType()==BulkJob.Type.UPDATE && (item.getState()==BulkItem.State.ACCEPTED || item.getState()==BulkItem.State.ALREADY_EXISTS)) {
            long outstanding=em.createQuery("select count(i) from BulkItem i where i.jobId=:job and i.eplId=:book and i.state not in :accepted",Long.class)
                .setParameter("job",job.getId()).setParameter("book",item.getEplId())
                .setParameter("accepted",List.of(BulkItem.State.ACCEPTED,BulkItem.State.ALREADY_EXISTS)).getSingleResult();
            if(outstanding==0) {
                int ready=em.createQuery("update UpdateCleanup e set e.replacementAccepted=true where e.jobId=:job and e.eplId=:book and e.immediate=true and e.automatic=true and e.state in :pending")
                    .setParameter("job",job.getId()).setParameter("book",item.getEplId())
                    .setParameter("pending",com.rlibanez.eplsync.torrent.updates.CleanupQueue.PENDING).executeUpdate();
                if(ready>0) publisher.publishEvent(new com.rlibanez.eplsync.torrent.updates.ImmediateUpdateCleanup.SubmissionAccepted());
            }
        }
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
        if(!(ex instanceof com.rlibanez.eplsync.exception.UserInputException)
                && !(ex instanceof TorrentOperationException)) return "No se pudo completar la operación torrent";
        String message = ex.getMessage();
        return message == null ? "Opciones inválidas" : message.substring(0, Math.min(500, message.length()));
    }
}
