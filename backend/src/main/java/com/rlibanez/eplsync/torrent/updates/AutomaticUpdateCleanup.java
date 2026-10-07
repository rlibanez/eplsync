package com.rlibanez.eplsync.torrent.updates;

import java.util.List;
import com.rlibanez.eplsync.security.*;
import com.rlibanez.eplsync.torrent.bulk.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** Only frontend jobs explicitly opting into cleanup are processed automatically. */
@Component
@ConditionalOnProperty(prefix="eplsync.torrent.bulk",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class AutomaticUpdateCleanup {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AutomaticUpdateCleanup.class);
    private final UpdatePlanRepository plans;
    private final BulkJobRepository jobs;
    private final AccountStore accounts;
    private final UpdateCleanupService cleanup;
    private final BulkStore bulk;
    private String after="";
    public AutomaticUpdateCleanup(UpdatePlanRepository plans,BulkJobRepository jobs,AccountStore accounts,UpdateCleanupService cleanup,BulkStore bulk) {
        this.plans=plans;this.jobs=jobs;this.accounts=accounts;this.cleanup=cleanup;this.bulk=bulk;
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void tick() {
        var pending=plans.automatic(after,List.of(UpdateCleanup.State.WAITING,UpdateCleanup.State.BLOCKED,UpdateCleanup.State.REQUESTED),
            org.springframework.data.domain.PageRequest.of(0,20));
        if(pending.isEmpty()) {after="";return;}
        var previous=SecurityContextHolder.getContext();
        try {
            synchronized(bulk) {
                var contexts=new java.util.HashMap<String,org.springframework.security.core.context.SecurityContext>();
                var eligible=new java.util.ArrayList<UpdatePlan>();
                for(var plan:pending) {
                    after=plan.getJobId();
                    var job=jobs.findById(plan.getJobId()).orElse(null);
                    if(job==null || job.getState()==BulkJob.State.CANCELLED || job.getEventActorId()==null) continue;
                    var account=accounts.find(job.getEventActorId());
                    if(account==null || !"ACTIVE".equals(account.status()) || account.mustChangePassword()
                        || !account.permissions().contains(Permission.TORRENT_CLEANUP)
                        || plan.getPreviousVersions()==PreviousVersions.REMOVE_TORRENT_AND_FILES
                            && !account.permissions().contains(Permission.TORRENT_FILES_DELETE)) continue;
                    var context=SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(account,null,
                        account.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList()));
                    contexts.put(plan.getJobId(),context);
                    eligible.add(plan);
                }
                cleanup.cleanAutomatic(eligible,plan -> SecurityContextHolder.setContext(contexts.get(plan.getJobId())));
            }
        } catch(RuntimeException ex) {
            log.warn("Limpieza automática pendiente: tipo={}",ex.getClass().getSimpleName());
        } finally {SecurityContextHolder.setContext(previous);}
    }
}
