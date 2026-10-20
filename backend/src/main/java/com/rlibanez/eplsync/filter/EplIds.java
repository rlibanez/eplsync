package com.rlibanez.eplsync.filter;

import java.util.*;
import com.rlibanez.eplsync.exception.UserInputException;

/** Positive identifiers, normalized once and bound as one set in list queries. */
public final class EplIds {
    private EplIds() {}
    public static List<Long> parse(String input) {
        if(input==null || input.isBlank()) return List.of();
        var ids=new LinkedHashSet<Long>();
        for(String token:input.trim().split("[,;\\s]+")) {
            try {if(!token.matches("[0-9]+")) throw new NumberFormatException();long id=Long.parseLong(token);if(id<=0) throw new NumberFormatException();ids.add(id);}
            catch(NumberFormatException ex) {throw new UserInputException("EPL ID debe contener identificadores enteros positivos separados por comas, punto y coma o espacios");}
        }
        if(ids.isEmpty()) throw new UserInputException("Indica al menos un EPL ID válido");
        return List.copyOf(ids);
    }
    public static String delimited(List<Long> ids) {return ","+ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+",";}
}
