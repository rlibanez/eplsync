package com.rlibanez.eplsync.torrent.updates;

import java.util.*;
import com.rlibanez.eplsync.dto.PageResponse;
import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.security.UpdatePreferences;
import com.rlibanez.eplsync.torrent.downloads.DownloadStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevisionUpdates {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public record Row(Long eplId,String title,Double registeredRevision,Double availableRevision,DownloadStatus status,String coverUrl,Boolean coverAvailable) {}
    // A failed request alone is not proof that the torrent reached the client.
    private static String evidence(String alias) {
        return "("+alias+".submittedAt is not null or "+alias+".discoveredAt is not null or "+alias+".completedAt is not null or "+alias+".lastSeenAt is not null)";
    }
    public static void validate(UpdatePreferences.Preferences input,int page,int size,String sort) {
        com.rlibanez.eplsync.config.QueryLimits.page(page,size);
        if(input==null || input.states()==null || input.states().isEmpty() || input.states().size()>DownloadStatus.values().length
            || input.states().stream().anyMatch(Objects::isNull) || new HashSet<>(input.states()).size()!=input.states().size())
            throw new UserInputException("Selecciona al menos un estado válido, sin duplicados");
        var fields=Map.of("eplId","b.eplId","title","b.title","registeredRevision","d.revision","availableRevision","b.revision","status","d.status");
        com.rlibanez.eplsync.config.TableOrdering.parse(sort, fields.keySet());
    }
    @Transactional(readOnly=true,timeout=120)
    public PageResponse<Row> search(UpdatePreferences.Preferences input,int page,int size,String sort) {
        return search(input,page,size,sort,null);
    }
    @Transactional(readOnly=true,timeout=120)
    public PageResponse<Row> search(UpdatePreferences.Preferences input,int page,int size,String sort,Long onlyId) {
        return search(input,page,size,sort,onlyId,null);
    }
    @Transactional(readOnly=true,timeout=120)
    public PageResponse<Row> search(UpdatePreferences.Preferences input,int page,int size,String sort,Long onlyId,DownloadStatus status) {
        validate(input,page,size,sort);
        var fields=Map.of("eplId","b.eplId","title","b.title","registeredRevision","d.revision","availableRevision","b.revision","status","d.status");
        var criteria = com.rlibanez.eplsync.config.TableOrdering.parse(sort, fields.keySet());
        var orderBy = criteria.stream().map(order -> fields.get(order.getProperty())+" "+order.getDirection().name()).collect(java.util.stream.Collectors.joining(","));
        if (criteria.stream().noneMatch(order -> order.getProperty().equals("eplId"))) orderBy += ", b.eplId asc";
        String from=" from CatalogBook b, DownloadRecord d where b.eplId=d.eplId and d.status in :states and d.revision<b.revision and "+evidence("d")
            +" and not exists (select x.id from DownloadRecord x where x.eplId=b.eplId and x.revision>=b.revision and "+evidence("x")+")"
            +" and not exists (select h.id from DownloadRecord h where h.eplId=b.eplId and h.status in :states and "+evidence("h")
            +" and (h.revision>d.revision or (h.revision=d.revision and h.id>d.id)))"
            +" and not exists (select i.id from BulkItem i, BulkJob j where i.jobId=j.id and i.eplId=b.eplId"
            +" and cast(function('json_extract',i.commandJson,'$.book.revision') as Double)>=b.revision"
            +" and i.state in (com.rlibanez.eplsync.torrent.bulk.BulkItem.State.PENDING,com.rlibanez.eplsync.torrent.bulk.BulkItem.State.IN_FLIGHT)"
            +" and j.state not in (com.rlibanez.eplsync.torrent.bulk.BulkJob.State.COMPLETED,com.rlibanez.eplsync.torrent.bulk.BulkJob.State.CANCELLED))";
        if(status!=null) from += " and d.status=com.rlibanez.eplsync.torrent.downloads.DownloadStatus."+status.name();
        if(onlyId!=null) from += " and b.eplId="+onlyId.longValue();
        long total=em.createQuery("select count(d.id)"+from,Long.class).setParameter("states",input.states())
            .setHint("jakarta.persistence.query.timeout",120000).getSingleResult();
        var rows=em.createQuery("select b.eplId,b.title,d.revision,b.revision,d.status,b.coverUrl,b.coverAvailable"+from+" order by "+orderBy,Object[].class)
            .setParameter("states",input.states()).setHint("jakarta.persistence.query.timeout",120000)
            .setFirstResult(Math.toIntExact((long)page*size)).setMaxResults(size).getResultList().stream()
            .map(r -> new Row((Long)r[0],(String)r[1],(Double)r[2],(Double)r[3],(DownloadStatus)r[4],(String)r[5],(Boolean)r[6])).toList();
        int pages=Math.toIntExact((total+size-1)/size);
        return new PageResponse<>(rows,new PageResponse.PageMeta(page,size,total,pages,page==0,page>=pages-1,page<pages-1,page>0));
    }
}
