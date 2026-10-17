package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.service.TorrentClientService;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** One availability rule for catalog sends, revision updates and automations. */
@Service
public class DownloadDependencies {
    private final DownloadRepository downloads;
    private final DownloadTrackingService tracking;
    private final TorrentClientService torrent;
    private final JdbcTemplate jdbc;
    public DownloadDependencies(DownloadRepository downloads,DownloadTrackingService tracking,TorrentClientService torrent,JdbcTemplate jdbc) {
        this.downloads=downloads;this.tracking=tracking;this.torrent=torrent;this.jdbc=jdbc;
    }
    public void refresh(Collection<Long> selected) {
        var ids=new ArrayList<>(selected);var hashes=new HashSet<String>();String instance=tracking.instanceId();
        for(int offset=0;offset<ids.size();offset+=500)
            for(var row:downloads.findByEplIdIn(ids.subList(offset,Math.min(offset+500,ids.size()))))
                if(instance.equals(row.getClientInstanceId())) hashes.add(row.getHash());
        if(hashes.isEmpty()) return;
        torrent.exclusiveClient(adapter -> {
            var remote=new HashMap<String,RemoteTorrent>();
            for(var row:hashes.size()>1000?adapter.listTorrents():adapter.listTorrents(hashes)) for(String alias:row.aliases()) remote.put(alias,row);
            tracking.observeSelected(remote,hashes);return null;
        });
    }
    public Map<Long,String> reusable(Map<Long,Double> revisions) {
        var result=new HashMap<Long,String>();var ids=new ArrayList<>(revisions.keySet());
        for(int offset=0;offset<ids.size();offset+=500) {
            var batch=ids.subList(offset,Math.min(offset+500,ids.size()));var args=new ArrayList<Object>();args.add(tracking.instanceId());args.addAll(batch);
            var rows=jdbc.queryForList("""
                SELECT i.epl_id,i.revision,i.job_id FROM torrent_bulk_items i JOIN torrent_bulk_jobs j ON j.id=i.job_id
                WHERE j.target_fingerprint=? AND j.state<>'CANCELLED'
                AND (i.state IN ('PENDING','IN_FLIGHT') OR (i.state IN ('ACCEPTED','ALREADY_EXISTS') AND EXISTS(
                    SELECT 1 FROM torrent_downloads d WHERE d.client_instance_id=j.target_fingerprint AND d.hash=i.hash AND d.status NOT IN ('ERROR','NOT_FOUND'))))
                AND i.epl_id IN ("""+String.join(",",Collections.nCopies(batch.size(),"?"))+") ORDER BY j.created_at DESC,j.id DESC",args.toArray());
            for(var row:rows) {
                long id=((Number)row.get("epl_id")).longValue();
                if(row.get("revision") instanceof Number revision && Double.compare(revision.doubleValue(),revisions.get(id))==0) result.putIfAbsent(id,(String)row.get("job_id"));
            }
        }
        return result;
    }
}
