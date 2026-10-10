package com.rlibanez.eplsync.updates;

import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class ApplicationUpdates {
    public record Status(boolean automatic,String state,String latestVersion,String releaseUrl,Instant checkedAt,boolean checking) {}
    private record Result(String state,String version,String url,Instant checkedAt) {}
    private final ApplicationVersion installed;
    private final ApplicationUpdateSettings settings;
    private final GitHubReleaseClient releases;
    private final Clock clock;
    private final AtomicBoolean checking=new AtomicBoolean();
    private volatile Result result=new Result("NOT_CHECKED",null,null,null);
    @org.springframework.beans.factory.annotation.Autowired
    public ApplicationUpdates(ApplicationVersion installed,ApplicationUpdateSettings settings,GitHubReleaseClient releases) {
        this(installed,settings,releases,Clock.systemUTC());
    }
    ApplicationUpdates(ApplicationVersion installed,ApplicationUpdateSettings settings,GitHubReleaseClient releases,Clock clock) {
        this.installed=installed;this.settings=settings;this.releases=releases;this.clock=clock;
    }
    public Status status() {
        var current=result;
        return new Status(settings.automatic(),current.state,current.version,current.url,current.checkedAt,checking.get());
    }
    public Status check(boolean automatic) {
        var last=result.checkedAt;
        Duration interval=automatic ? Duration.ofHours(24) : Duration.ofMinutes(1);
        if(automatic && !settings.automatic() || last!=null && clock.instant().isBefore(last.plus(interval)) || !checking.compareAndSet(false,true)) return status();
        try {
            // Another completed request may have populated the cache before we acquired the turn.
            last=result.checkedAt;
            if(last!=null && clock.instant().isBefore(last.plus(interval))) return status();
            var release=releases.latest();
            if(release==null) result=new Result("NO_RELEASE",null,null,clock.instant());
            else {
                var newer=newer(installed.get().version(),release.version());
                result=new Result(newer==null ? "UNKNOWN_VERSION" : newer ? "AVAILABLE" : "UP_TO_DATE",release.version(),release.url(),clock.instant());
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();result=new Result("UNAVAILABLE",result.version,result.url,clock.instant());
        } catch(Exception ex) {
            result=new Result("UNAVAILABLE",result.version,result.url,clock.instant());
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("No se pudo comprobar la última versión de EPL Sync ({})",ex.getClass().getSimpleName());
        } finally {checking.set(false);}
        return status();
    }
    static Boolean newer(String installed,String latest) {
        if(installed==null || !installed.matches("v?\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}(-[A-Za-z0-9.-]+)?")) return null;
        var current=installed.replaceFirst("^v","").split("-",2)[0].split("\\.");var candidate=latest.split("\\.");
        for(int i=0;i<3;i++) {int diff=Integer.compare(Integer.parseInt(candidate[i]),Integer.parseInt(current[i]));if(diff!=0) return diff>0;}
        return installed.contains("-");
    }
}
