package com.rlibanez.eplsync.reports;

import org.junit.jupiter.api.Test;
import com.rlibanez.eplsync.events.EventContext;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReportSnapshotsTests {
    public record Item(int eplId,String title,String action) {}
    @Test void pagesImmutableDetailsAndEnforcesOwnerSortAndSize() {
        try(var store=new ReportSnapshots()) {
            var owner=new EventContext.Actor("1","alice","USER");
            String id=EventContext.withActor(owner,() -> {
                try(var writer=store.create()) {
                    for(int i=0;i<2001;i++) writer.add("books",new Item(i,"Book "+i,i%2==0?"UPDATE":"UNCHANGED"));
                    writer.finish();return writer.id;
                }
            });
            var page=EventContext.withActor(owner,() -> store.page(id,"books",1,20,List.of("eplId,desc"),Set.of("eplId","action"),
                List.of(new ReportSnapshots.Filter("action","=","UPDATE")),Item.class));
            assertThat(page.meta().totalItems()).isEqualTo(1001);
            assertThat(page.items().getFirst().eplId()).isEqualTo(1960);
            assertThatThrownBy(() -> store.page(id,"books",0,20,List.of(),Set.of(),List.of(),Item.class)).hasMessageContaining("caducado");
            EventContext.withActor(owner,() -> {
                assertThatThrownBy(() -> store.page(id,"books",0,1001,List.of(),Set.of(),List.of(),Item.class)).hasMessageContaining("Página inválida");
                assertThatThrownBy(() -> store.page(id,"books",0,20,List.of("title; DROP TABLE details,asc"),Set.of("title"),List.of(),Item.class)).hasMessageContaining("inválido");return null;
            });
        }
    }
    @Test void textSortingUsesSpanishOrderBeforePagination() {
        try(var store=new ReportSnapshots()) {
            var owner=new EventContext.Actor("1","alice","USER");
            var titles=List.of("Zeta","Órbita","Ñandú","Nube","¿Quién?","Árbol 10","Árbol 2","Éxodo");
            String id=EventContext.withActor(owner,()-> {
                try(var writer=store.create()) {
                    for(int i=0;i<titles.size();i++) writer.add("books",new Item(i,titles.get(i),"UPDATE"));
                    writer.finish();return writer.id;
                }
            });
            var actual=new ArrayList<String>();
            for(int page=0;page<4;page++) {
                int current=page;
                actual.addAll(EventContext.withActor(owner,()->store.page(id,"books",current,2,List.of("title,asc"),Set.of("title"),List.of(),Item.class)).items().stream().map(Item::title).toList());
            }
            assertThat(actual).containsExactly("Árbol 2","Árbol 10","Éxodo","Nube","Ñandú","Órbita","¿Quién?","Zeta");
        }
    }
    @Test void incompleteReportsAreDiscardedAndRetentionIsBounded() {
        try(var store=new ReportSnapshots()) {
            String incomplete;
            try(var writer=store.create()) {incomplete=writer.id;writer.add("books",new Item(1,"Book","UPDATE"));}
            assertThatThrownBy(() -> store.page(incomplete,"books",0,20,List.of(),Set.of(),List.of(),Item.class)).hasMessageContaining("caducado");
            var ids=new ArrayList<String>();
            for(int i=0;i<5;i++) try(var writer=store.create()) {ids.add(writer.id);writer.finish();}
            assertThatThrownBy(() -> store.page(ids.getFirst(),"books",0,20,List.of(),Set.of(),List.of(),Item.class)).hasMessageContaining("caducado");
        }
    }
}
