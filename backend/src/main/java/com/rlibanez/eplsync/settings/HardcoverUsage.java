package com.rlibanez.eplsync.settings;

import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Last reported daily quota, bounded and isolated by credential; never polls Hardcover. */
final class HardcoverUsage {
    public record Snapshot(long limit,long remaining,long used,Instant observedAt,Instant resetAt) {}
    private final Map<String,Snapshot> snapshots=new LinkedHashMap<>(8,0.75f,true);
    synchronized Snapshot snapshot(String key) {
        if(key.isBlank()) return null;
        var value=snapshots.get(fingerprint(key));
        return value!=null && value.resetAt().isAfter(Instant.now())?value:null;
    }
    synchronized void capture(String key,String rateLimit,String policy) {
        var quota=parameters(policy);var state=parameters(rateLimit);
        Long limit=quota.get("q"),window=quota.get("w"),remaining=state.get("r"),reset=state.get("t");
        if(limit==null || window==null || window!=86400 || limit<=0 || remaining==null || remaining>limit || reset==null || reset>86400) return;
        var now=Instant.now();
        snapshots.put(fingerprint(key),new Snapshot(limit,remaining,limit-remaining,now,now.plusSeconds(reset)));
        while(snapshots.size()>8) snapshots.remove(snapshots.keySet().iterator().next());
    }
    private static Map<String,Long> parameters(String header) {
        if(header==null || header.length()>4096) return Map.of();
        for(String entry:header.split(",")) {
            var parts=entry.strip().split(";");
            if(parts.length==0 || !parts[0].strip().equalsIgnoreCase("\"daily\"")) continue;
            var values=new HashMap<String,Long>();
            for(int i=1;i<parts.length;i++) {
                var pair=parts[i].strip().split("=",2);
                if(pair.length==2 && pair[1].matches("[0-9]{1,12}")) values.put(pair[0].strip(),Long.parseLong(pair[1]));
            }
            return values;
        }
        return Map.of();
    }
    private static String fingerprint(String key) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException("SHA-256 no disponible",ex);}
    }
}
