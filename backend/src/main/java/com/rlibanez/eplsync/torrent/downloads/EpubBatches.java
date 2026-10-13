package com.rlibanez.eplsync.torrent.downloads;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.rlibanez.eplsync.exception.TorrentOperationException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded, short-lived manifests. No open files or credentials are retained. */
@Service
public class EpubBatches {
    private final EpubFiles files;
    private final Semaphore preparing=new Semaphore(1);
    private final Map<String,Pending> batches=new HashMap<>();
    private record Pending(String session,Instant expires,EpubFiles.Plan plan) {}
    public record Ready(String url,int fileCount,boolean archive,List<String> sizeWarnings) {}
    public EpubBatches(EpubFiles files) { this.files=files; }
    public Ready prepare(String session,List<String> ids) {
        if(!preparing.tryAcquire()) throw new TorrentOperationException(HttpStatus.CONFLICT,"Hay otra descarga múltiple en preparación; inténtalo de nuevo en unos segundos");
        try {
            synchronized(this) { expire(); if(batches.size()>=20 || batches.values().stream().filter(p -> p.session().equals(session)).count()>=2)
                throw new TorrentOperationException(HttpStatus.CONFLICT,"Hay demasiadas descargas pendientes de iniciar; inténtalo de nuevo en unos minutos"); }
            var plan=files.prepare(ids);
            String token=UUID.randomUUID().toString();
            synchronized(this) { batches.put(token,new Pending(session,Instant.now().plusSeconds(120),plan)); }
            return new Ready("/api/torrent/downloads/epub-batches/"+token,plan.items().size(),plan.archive(),plan.items().stream().filter(entry -> java.util.Objects.requireNonNull(entry).sizeMismatch()).map(p -> p.file().name()).toList());
        } finally { preparing.release(); }
    }
    synchronized EpubFiles.Plan take(String session,String token) {
        expire();
        var pending=batches.get(token);
        if(pending==null || !pending.session().equals(session)) throw new TorrentOperationException(HttpStatus.NOT_FOUND,"La descarga ha caducado o no pertenece a esta sesión; vuelve a seleccionar los registros");
        batches.remove(token);
        return pending.plan();
    }
    private void expire() { batches.values().removeIf(p -> !p.expires().isAfter(Instant.now())); }
}
