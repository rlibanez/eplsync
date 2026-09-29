package com.rlibanez.eplsync.torrent.updates;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.filter.CatalogBookFilter;
import com.rlibanez.eplsync.specification.CatalogBookSpecifications;
import com.rlibanez.eplsync.repository.CatalogBookRepository;
import com.rlibanez.eplsync.service.TorrentDownloadService;
import com.rlibanez.eplsync.torrent.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

@Service
public class UpdatePlanner {
    private final DownloadRepository downloads;
    private final CatalogBookRepository books;
    private final DownloadTrackingService tracking;
    private final TorrentProperties properties;
    private final MagnetLinkBuilder magnets;
    private final TorrentDownloadService preparation;
    private final BulkStore bulk;
    private final BulkItemRepository items;
    private final UpdatePlanRepository plans;
    private final UpdateCleanupRepository cleanup;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public UpdatePlanner(DownloadRepository downloads, CatalogBookRepository books, DownloadTrackingService tracking,
            TorrentProperties properties, MagnetLinkBuilder magnets, TorrentDownloadService preparation, BulkStore bulk,
            BulkItemRepository items, UpdatePlanRepository plans, UpdateCleanupRepository cleanup) {
        this.downloads = downloads; this.books = books; this.tracking = tracking; this.properties = properties;
        this.magnets = magnets; this.preparation = preparation; this.bulk = bulk; this.items = items;
        this.plans = plans; this.cleanup = cleanup;
    }

    public record Existing(String id, double revision, String hash, DownloadStatus status) {}
    public record Candidate(Long eplId, String title, double catalogRevision, List<Existing> existingDownloads,
            List<String> targetHashes) {}
    public record Snapshot(List<Candidate> items) {}

    public enum Selection { NEW, UPDATES, BOTH }

    @Transactional(readOnly = true)
    public List<Candidate> preview(Long eplId, boolean includeNotFound, MultipleHashes multipleHashes) {
        var filter = new CatalogBookFilter();
        filter.setEplId(eplId);
        return preview(filter, includeNotFound, multipleHashes, Selection.UPDATES);
    }

    @Transactional(readOnly = true)
    public List<Candidate> preview(CatalogBookFilter filter, boolean includeNotFound,
            MultipleHashes multipleHashes, Selection selection) {
        if (filter.getEplId() != null && filter.getEplId() <= 0)
            throw new IllegalArgumentException("eplId debe ser positivo");
        var policy = multipleHashes == null ? properties.getBulk().getMultipleHashes() : multipleHashes;
        String instance = tracking.instanceId();
        var grouped = new HashMap<Long, List<DownloadRecord>>();
        for (var row : downloads.findByClientInstanceId(instance))
            grouped.computeIfAbsent(row.getEplId(), key -> new ArrayList<>()).add(row);
        var active = new HashSet<>(items.activeBooks(instance,
                List.of(BulkItem.State.PENDING, BulkItem.State.IN_FLIGHT)));
        // Project only selection fields, avoiding loading every synopsis in a large catalogue.
        var selected = books.findBy(CatalogBookSpecifications.fromFilter(filter),
                query -> query.as(CatalogBookRepository.UpdateIdentity.class).all());
        var result = new ArrayList<Candidate>();
        for (var book : selected) {
            if (active.contains(book.getEplId()) || book.getRevision() == null || !Double.isFinite(book.getRevision())) continue;
            var history = grouped.getOrDefault(book.getEplId(), List.of());
            boolean isNew = history.isEmpty();
            if (isNew && selection == Selection.UPDATES || !isNew && selection == Selection.NEW) continue;
            if (!isNew) {
                var eligible = history.stream().filter(row -> present(row.getStatus())
                        || includeNotFound && row.getStatus() == DownloadStatus.NOT_FOUND).toList();
                if (eligible.isEmpty() || eligible.stream().anyMatch(row -> row.getRevision() >= book.getRevision())) continue;
            }
            var hashes = magnets.hashes(book.getLinks());
            if (hashes.isEmpty() || hashes.size() > 1 && policy == MultipleHashes.SKIP) continue;
            var targets = policy == MultipleHashes.ALL ? hashes : List.of(hashes.getFirst());
            // Never reinterpret an existing hash as a different revision.
            if (history.stream().anyMatch(row -> targets.contains(row.getHash()) && (present(row.getStatus()) || row.getStatus() == DownloadStatus.UNKNOWN))) continue;
            if (history.stream().anyMatch(row -> targets.contains(row.getHash()) && row.getRevision() != book.getRevision().doubleValue())) continue;
            result.add(new Candidate(book.getEplId(), book.getTitle(), book.getRevision(), history.stream()
                    .filter(row -> row.getRevision() < book.getRevision()).map(row -> new Existing(row.getId(),
                            row.getRevision(), row.getHash(), row.getStatus())).toList(), targets));
        }
        result.sort(Comparator.comparing(Candidate::eplId));
        return result;
    }

    private boolean present(DownloadStatus status) {
        return status != DownloadStatus.ERROR && status != DownloadStatus.UNKNOWN && status != DownloadStatus.NOT_FOUND;
    }

    @Transactional
    public BulkStore.View create(Long eplId, boolean includeNotFound, UpdateRequest request) {
        var filter = new CatalogBookFilter();
        filter.setEplId(eplId);
        return create(filter, includeNotFound, request, Selection.UPDATES);
    }

    @Transactional
    public BulkStore.View create(CatalogBookFilter filter, boolean includeNotFound,
            UpdateRequest request, Selection selection) {
        var input = request == null ? new UpdateRequest(null, null, null, null, null, null) : request;
        if (input.options() != null && input.options().hash() != null)
            throw new IllegalArgumentException("Las actualizaciones no admiten options.hash");
        var candidates = preview(filter, includeNotFound, input.multipleHashes(), selection);
        var commands = new ArrayList<TorrentDownload>();
        for (int offset = 0; offset < candidates.size(); offset += 500) {
            var chunk = candidates.subList(offset, Math.min(candidates.size(), offset + 500));
            var selected = new HashMap<Long, com.rlibanez.eplsync.model.CatalogBook>();
            books.findAllById(chunk.stream().map(Candidate::eplId).toList()).forEach(book -> selected.put(book.getEplId(), book));
            for (var candidate : chunk) for (var hash : candidate.targetHashes()) {
                var options = input.options();
                commands.add(preparation.prepare(selected.get(candidate.eplId()), options == null
                        ? new TorrentDownloadRequest(hash, null, null, null, null)
                        : new TorrentDownloadRequest(hash, options.start(), options.savePath(), options.rename(), options.qbittorrent())));
            }
        }
        var job = bulk.createPrepared(commands, input.bulk());
        var plan = new UpdatePlan();
        plan.setJobId(job.jobId()); plan.setClientInstanceId(tracking.instanceId());
        plan.setPreviousVersions(input.policy()); plan.setCreatedAt(Instant.now());
        plan.setSnapshot(mapper.writeValueAsString(new Snapshot(candidates))); plans.save(plan);
        for (var candidate : candidates) for (var old : candidate.existingDownloads()) {
            var entry = new UpdateCleanup();
            entry.setJobId(job.jobId()); entry.setDownloadId(old.id()); entry.setEplId(candidate.eplId());
            entry.setHash(old.hash()); entry.setUpdatedAt(Instant.now());
            entry.setState(input.policy() == PreviousVersions.KEEP ? UpdateCleanup.State.KEPT : UpdateCleanup.State.WAITING);
            cleanup.save(entry);
        }
        return job;
    }
}
