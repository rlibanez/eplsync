package com.rlibanez.eplsync.security;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Bounded per-process counters. Never trust forwarded addresses supplied by arbitrary clients. */
@Component
public class LoginThrottle {
    private record Attempts(long until,int count) {}
    private final Map<String,Attempts> attempts=new LinkedHashMap<>();
    public synchronized void check(String key,int limit) {
        long now=Instant.now().getEpochSecond();
        attempts.entrySet().removeIf(e->e.getValue().until()<now);
        var previous=attempts.get(key);
        if(previous!=null && previous.count()>=limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Demasiados intentos; espera unos minutos");
        if(attempts.size()>=10000 && previous==null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Demasiados intentos; espera unos minutos");
        attempts.put(key,new Attempts(previous==null?now+300:previous.until(),previous==null?1:previous.count()+1));
    }
}
