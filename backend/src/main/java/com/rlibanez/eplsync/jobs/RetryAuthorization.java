package com.rlibanez.eplsync.jobs;

import java.time.Instant;
import com.rlibanez.eplsync.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Retry jobs use the creator's current permissions when each external operation is dispatched. */
@Service
public class RetryAuthorization {
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    public RetryAuthorization(JdbcTemplate jdbc,AccountStore accounts) {this.jdbc=jdbc;this.accounts=accounts;}
    public boolean torrent(String id,boolean cleanup,boolean deleteFiles) {return torrent(id,cleanup,deleteFiles,false);}
    public boolean torrent(String id,boolean cleanup,boolean deleteFiles,boolean deleteHistory) {
        var owners=jdbc.queryForList("SELECT owner_id FROM job_retry_attempts WHERE kind='downloads' AND job_id=? ORDER BY created_at DESC,id DESC LIMIT 1",String.class,id);
        if(owners.isEmpty()) owners=jdbc.queryForList("SELECT owner_id FROM job_retry_jobs WHERE kind='downloads' AND child_id=?",String.class,id);
        if(owners.isEmpty()) owners=jdbc.queryForList("SELECT r.owner_id FROM scheduled_automation_runs r JOIN scheduled_automation_jobs j ON j.run_id=r.id WHERE j.kind='downloads' AND j.job_id=?",String.class,id);
        if(owners.isEmpty()) {
            var original=jdbc.queryForList("SELECT event_actor_id FROM torrent_bulk_jobs WHERE id=? AND event_actor_id IS NOT NULL",String.class,id);
            if(original.isEmpty()) original=jdbc.queryForList("SELECT actor_id FROM torrent_update_cleanup WHERE job_id=? AND actor_id IS NOT NULL ORDER BY created_at,id LIMIT 1",String.class,id);
            if(original.isEmpty()) return true; // Legacy/system jobs without a persisted identity.
            owners=original;
        }
        String owner=owners.getFirst();var account=owner==null?null:accounts.find(owner);
        return account!=null && account.status().equals("ACTIVE") && !account.mustChangePassword()
            && (account.temporaryExpiresAt()==null || account.temporaryExpiresAt().isAfter(Instant.now()))
            && account.permissions().contains(cleanup?Permission.TORRENT_CLEANUP:deleteHistory?Permission.DOWNLOADS_DELETE:Permission.TORRENT_SEND)
            && (!deleteHistory || account.permissions().contains(Permission.DOWNLOADS_DELETE))
            && (!cleanup || !deleteFiles || account.permissions().contains(Permission.TORRENT_FILES_DELETE));
    }
}
