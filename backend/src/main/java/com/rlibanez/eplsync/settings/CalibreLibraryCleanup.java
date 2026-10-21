package com.rlibanez.eplsync.settings;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.rlibanez.eplsync.exception.UserInputException;

/** One plan per library: deletion targets never serve as retained copies in that plan. */
@Service
public class CalibreLibraryCleanup {
    public record Input(boolean oldRevisions,boolean duplicates,List<String> ids,Map<String,String> retained,String previewId) {}
    public record Copy(String id,String title,List<String> formats) {}
    public record Group(String key,long eplId,String revision,List<Copy> copies,String retainedId,boolean requiresChoice) {}
    public record Candidate(long eplId,String title,String revision,String oldId,String retainedId,String retainedRevision,String reason,String problem) {}
    public record Plan(List<Candidate> candidates,List<Group> groups) {}
    private record Entry(String id,long eplId,BigDecimal revision,String title) {}
    private final JdbcTemplate jdbc;
    private final CalibreClient client;
    public CalibreLibraryCleanup(JdbcTemplate jdbc,CalibreClient client) {this.jdbc=jdbc;this.client=client;}
    public Plan plan(Input input,CalibreSettings.Connection connection) {
        if(input==null || !input.oldRevisions() && !input.duplicates()) throw new UserInputException("Selecciona al menos un tipo de limpieza");
        var ids=input.ids()==null?List.<String>of():input.ids();
        if(ids.stream().anyMatch(id->id==null || !id.matches("[1-9][0-9]{0,18}")) || new HashSet<>(ids).size()!=ids.size()) throw new UserInputException("Selecciona entradas de Calibre sin duplicados");
        String scope=ids.isEmpty()?"":" AND epl_id IN (SELECT epl_id FROM calibre_books WHERE active=1 AND cast(calibre_id AS TEXT) IN (SELECT value FROM json_each(?)))";
        Object[] args=ids.isEmpty()?new Object[0]:new Object[]{tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(ids)};
        var entries=jdbc.query("SELECT calibre_id,epl_id,revision,title FROM calibre_books WHERE active=1 AND epl_id>0 AND revision>0"+scope+" ORDER BY epl_id,revision DESC,calibre_id",(r,n)->new Entry(r.getString(1),r.getLong(2),new BigDecimal(r.getString(3)).stripTrailingZeros(),r.getString(4)),args);
        var selected=new HashSet<>(ids);
        return build(entries,input,connection,entry->selected.isEmpty() || selected.contains(entry.id()),()->{},true);
    }
    public Plan snapshot(String jobId,long eplId,Input input,CalibreSettings.Connection connection,Runnable checkpoint) {
        var entries=jdbc.query("SELECT calibre_id,epl_id,revision,title FROM calibre_cleanup_sources WHERE job_id=? AND epl_id=? ORDER BY CAST(revision AS NUMERIC) DESC,calibre_id",(r,n)->new Entry(r.getString(1),r.getLong(2),new BigDecimal(r.getString(3)).stripTrailingZeros(),r.getString(4)),jobId,eplId);
        var chosen=new HashSet<>(jdbc.queryForList("SELECT cast(calibre_id AS TEXT) FROM calibre_cleanup_sources WHERE job_id=? AND epl_id=? AND chosen=1",String.class,jobId,eplId));
        return build(entries,input,connection,entry->chosen.contains(entry.id()),checkpoint,false);
    }
    private Plan build(List<Entry> entries,Input input,CalibreSettings.Connection connection,java.util.function.Predicate<Entry> selected,Runnable checkpoint,boolean readFormats) {
        Map<String,String> choices=input.retained()==null?Map.of():input.retained();
        var books=new LinkedHashMap<Long,List<Entry>>();for(var entry:entries) books.computeIfAbsent(entry.eplId(),key->new ArrayList<>()).add(entry);
        var groups=new ArrayList<Group>();var candidates=new ArrayList<Candidate>();var used=new HashSet<String>();
        for(var book:books.values()) {
            var revisions=new LinkedHashMap<BigDecimal,List<Entry>>();for(var entry:book) revisions.computeIfAbsent(entry.revision(),key->new ArrayList<>()).add(entry);
            var highest=book.getFirst().revision();
            for(var same:revisions.values()) {
                boolean older=same.getFirst().revision().compareTo(highest)<0;
                if(input.oldRevisions() && older) continue;
                var groupEntries=same;
                boolean deletesOlder=input.oldRevisions() && book.stream().anyMatch(e->e.revision().compareTo(highest)<0 && selected.test(e));
                boolean deletesDuplicates=input.duplicates() && same.size()>1 && same.stream().anyMatch(e->selected.test(e));
                if(!deletesOlder && !deletesDuplicates) continue;
                String key=book.getFirst().eplId()+":"+same.getFirst().revision().toPlainString();
                String keep=choices.getOrDefault(key,same.getFirst().id());used.add(key);
                if(keep==null || same.stream().noneMatch(e->e.id().equals(keep))) throw new UserInputException("La copia elegida no pertenece al grupo; vuelve a previsualizar");
                var retained=same.stream().filter(e->e.id().equals(keep)).findFirst().orElseThrow();
                List<Copy> copies=new ArrayList<>();
                if(same.size()>1) for(var entry:groupEntries) {
                    checkpoint.run();
                    if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    copies.add(new Copy(entry.id(),entry.title(),readFormats?List.copyOf(client.formats(connection,Long.parseLong(entry.id()))):List.of()));
                }
                boolean different=deletesDuplicates && copies.stream().map(copy->new TreeSet<>(copy.formats())).distinct().count()>1;
                if(same.size()>1) groups.add(new Group(key,retained.eplId(),retained.revision().toPlainString(),List.copyOf(copies),keep,different && !choices.containsKey(key)));
                for(var old:input.oldRevisions()?book:same) {
                    boolean lower=old.revision().compareTo(retained.revision())<0;
                    boolean duplicate=old.revision().compareTo(retained.revision())==0 && !old.id().equals(keep);
                    if((input.oldRevisions() && lower || input.duplicates() && duplicate) && selected.test(old)) {
                        candidates.add(new Candidate(old.eplId(),old.title(),old.revision().toPlainString(),old.id(),keep,retained.revision().toPlainString(),lower?"OLD_REVISION":"DUPLICATE",duplicate && different && !choices.containsKey(key)?"Los duplicados tienen formatos diferentes; elige explícitamente la copia que se conservará":null));
                        checkpoint.run();
                    }
                }
            }
        }
        if(choices.keySet().stream().anyMatch(key->entries.stream().anyMatch(e->key.startsWith(e.eplId()+":")) && !used.contains(key))) throw new UserInputException("Los grupos de Calibre cambiaron; vuelve a previsualizar sin las elecciones anteriores");
        var removed=new HashSet<String>();for(var item:candidates) if(!removed.add(item.oldId())) throw new IllegalStateException("Duplicate cleanup target");
        for(var item:candidates) if(removed.contains(item.retainedId())) throw new IllegalStateException("Retained copy is a deletion target");
        return new Plan(List.copyOf(candidates),List.copyOf(groups));
    }
}
