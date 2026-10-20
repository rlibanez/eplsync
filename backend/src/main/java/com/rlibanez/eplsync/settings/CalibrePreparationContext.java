package com.rlibanez.eplsync.settings;

import java.util.function.Supplier;

/** Preparation progress is observational; only the final transaction activates staged jobs. */
public final class CalibrePreparationContext {
    public interface Listener {void update(String phase,long processed,long total);}
    private record Context(Listener listener,boolean staged,java.util.Set<String> downloads) {}
    private static final ThreadLocal<Context> current=new ThreadLocal<>();
    private CalibrePreparationContext() {}
    public static boolean staged() {var context=current.get();return context!=null && context.staged();}
    public static void createdDownload(String id) {var context=current.get();if(context!=null && context.staged()) context.downloads().add(id);}
    public static java.util.Set<String> downloads() {var context=current.get();return context==null?java.util.Set.of():java.util.Set.copyOf(context.downloads());}
    public static void update(String phase,long processed,long total) {var context=current.get();if(context!=null) context.listener().update(phase,processed,total);}
    public static <T> T run(Listener listener,boolean staged,Supplier<T> action) {
        var previous=current.get();current.set(new Context(listener,staged,new java.util.HashSet<>()));
        try {return action.get();} finally {if(previous==null)current.remove();else current.set(previous);}
    }
}
