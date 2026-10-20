package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.events.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shares the torrent cancellation transaction; never performs remote operations. */
@Service
public class DownloadCancellation {
    public static final String REASON="El trabajo de descarga relacionado se canceló";
    private final JdbcTemplate jdbc;
    private final EventJournal events;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DownloadCancellation.class);
    private static final String ANCESTORS="""
        WITH RECURSIVE cancelled_ancestors(id) AS (
          SELECT ? UNION SELECT r.parent_id FROM job_retry_jobs r JOIN cancelled_ancestors a ON r.child_id=a.id WHERE r.kind='downloads'
        )
        """;
    // Legacy retries can affect only part of the original job. Keep a surviving
    // retry/accepted download as a dependency rather than cancelling that row.
    private static final String AFFECTED="""
        coalesce((i.download_job_id=? OR (
          i.download_job_id IN (SELECT id FROM cancelled_ancestors)
          AND EXISTS(SELECT 1 FROM torrent_bulk_items b WHERE b.job_id=? AND b.epl_id=i.epl_id AND b.revision=CAST(i.revision AS REAL))
          AND NOT EXISTS(
            WITH RECURSIVE family(id) AS (
              SELECT i.download_job_id UNION SELECT r.child_id FROM job_retry_jobs r JOIN family f ON r.parent_id=f.id WHERE r.kind='downloads'
            )
            SELECT 1 FROM torrent_bulk_items b JOIN torrent_bulk_jobs j ON j.id=b.job_id
            WHERE b.job_id IN (SELECT id FROM family) AND j.state<>'CANCELLED' AND b.epl_id=i.epl_id AND b.revision=CAST(i.revision AS REAL)
            AND (b.state IN ('PENDING','IN_FLIGHT') OR b.state IN ('ACCEPTED','ALREADY_EXISTS') AND EXISTS(
              SELECT 1 FROM torrent_downloads d WHERE d.client_instance_id=j.target_fingerprint AND d.hash=b.hash AND d.status NOT IN ('ERROR','NOT_FOUND')
            ))
          )
        )),false)
        """;
    private static final String UNFINISHED=" AND i.state IN ('PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD','SENDING','UNCERTAIN','CLEANUP_PENDING')";
    public DownloadCancellation(JdbcTemplate jdbc,EventJournal events) {this.jdbc=jdbc;this.events=events;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void propagate(String download) {
        var jobs=jdbc.queryForList(ANCESTORS+"SELECT DISTINCT i.job_id FROM calibre_job_items i JOIN calibre_jobs j ON j.id=i.job_id WHERE j.state IN ('QUEUED','RUNNING','PAUSED') AND i.download_job_id IN (SELECT id FROM cancelled_ancestors) AND "+AFFECTED, String.class,download,download,download);
        for(String job:jobs) {
            boolean exclusive=jdbc.queryForObject(ANCESTORS+"SELECT count(*) FROM calibre_job_items i WHERE i.job_id=? AND NOT "+AFFECTED,Long.class,download,job,download,download)==0;
            int affected=change(download,job,"cancelled_by_download_job_id=?,requested_cleanup_policy=coalesce(requested_cleanup_policy,cleanup_policy),requested_cleanup_ids=coalesce(requested_cleanup_ids,cleanup_ids),cleanup_cancelled=CASE WHEN state='CLEANUP_PENDING' THEN true ELSE cleanup_cancelled END",UNFINISHED,download);
            if(affected==0) continue;
            change(download,job,"cleanup_policy='KEEP',cleanup_ids='[]'",UNFINISHED);
            change(download,job,"state=completion_state,message=coalesce(message || '. ','') || ?"," AND i.state='CLEANUP_PENDING'",REASON+". Limpieza de revisiones anteriores cancelada");
            int cancelled=change(download,job,"state='CANCELLED',message=?,next_check=0,wait_until=NULL"," AND i.state IN ('PENDING','WAITING_DOWNLOAD','BLOCKED_DOWNLOAD')",REASON+". No se enviará este elemento a Calibre");
            change(download,job,"message=coalesce(message || '. ','') || ?"," AND i.state IN ('SENDING','UNCERTAIN')",REASON+"; se conservará y comprobará el resultado del envío en curso");
            String reason=exclusive?REASON+"; trabajo de Calibre cancelado":REASON+"; solo se cancelan los elementos vinculados, el resto conserva su estado";
            jdbc.update("UPDATE calibre_jobs SET state=CASE WHEN ? THEN 'CANCELLED' ELSE state END,message=?,next_check=0,next_probe=0,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING','PAUSED')",exclusive,reason,Instant.now().toString(),job);
            var details=Map.<String,Object>of("command","cancel","jobId",job,"downloadJobId",download,"reason",reason,"selected",affected);
            String operation=UUID.randomUUID().toString();var actor=EventContext.actor();
            events.recordAs(actor,EventJournal.Category.JOB,"CALIBRE_JOB_CONTROL",EventJournal.Outcome.STARTED,EventContext.origin(),operation,details);
            events.recordAs(actor,EventJournal.Category.JOB,"CALIBRE_JOB_CONTROL",EventJournal.Outcome.CANCELLED,EventContext.origin(),operation,details);
            log.info("Cancelación de descarga propagada a Calibre: descarga={}, trabajo={}, trabajoCancelado={}, elementosAfectados={}, elementosCancelados={}",download,job,exclusive,affected,cancelled);
        }
    }
    private int change(String download,String job,String assignment,String states,Object... values) {
        var arguments=new ArrayList<Object>();arguments.add(download);Collections.addAll(arguments,values);arguments.add(job);arguments.add(download);arguments.add(download);
        return jdbc.update(ANCESTORS+"UPDATE calibre_job_items AS i SET "+assignment+" WHERE i.job_id=? AND "+AFFECTED+states,arguments.toArray());
    }
}
