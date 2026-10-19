package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.exception.UserInputException;
import com.rlibanez.eplsync.ordering.StateOperator;
import java.util.*;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.json.JsonMapper;

/** Bound JSON sets keep multi-value filters below SQLite's parameter limit. */
final class JobItemFilters {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private JobItemFilters() {}
    static void append(StringBuilder where,List<Object> args,MultiValueMap<String,String> params,String textKey,String textColumn) {
        boolean excludeIds=StateOperator.excluded(params.getFirst("eplIdOperator")==null?"eq":params.getFirst("eplIdOperator"));
        var ids=new LinkedHashSet<Long>();
        for(String input:params.getOrDefault("eplId",List.of())) {
            if(input==null) throw new UserInputException("EPL ID inválido");
            for(String token:input.trim().split("[,;\\s]+")) {
                if(token.isEmpty()) continue;
                try {long id=Long.parseLong(token);if(id<=0) throw new NumberFormatException();ids.add(id);}
                catch(NumberFormatException ex) {throw new UserInputException("EPL ID debe contener identificadores enteros positivos");}
            }
        }
        if(!ids.isEmpty()) {
            where.append(" AND coalesce(epl_id,0) ").append(excludeIds?"NOT IN":"IN").append(" (SELECT value FROM json_each(?))");
            args.add(JSON.writeValueAsString(ids));
        }
        text(where,args,params,textKey,textColumn);
    }
    static void text(StringBuilder where,List<Object> args,MultiValueMap<String,String> params,String textKey,String textColumn) {
        boolean excludeText=StateOperator.excluded(params.getFirst(textKey+"Operator")==null?"eq":params.getFirst(textKey+"Operator"));
        var terms=new LinkedHashSet<String>();
        for(String value:params.getOrDefault(textKey,List.of())) {
            if(value==null || value.length()>4096) throw new UserInputException("Filtro de texto inválido");
            if(!value.isBlank()) terms.add(value.trim());
        }
        if(!terms.isEmpty()) {
            where.append(excludeText?" AND NOT EXISTS (":" AND EXISTS (")
                .append("SELECT 1 FROM json_each(?) term WHERE instr(lower(coalesce(").append(textColumn).append(",'')),lower(term.value))>0)");
            args.add(JSON.writeValueAsString(terms));
        }
    }
    static void states(StringBuilder where,List<Object> args,String state,String operator,Set<String> allowed) {
        boolean exclude=StateOperator.excluded(operator);
        if(state==null) throw new UserInputException("Estado inválido");
        if(state.isEmpty()) return;
        var values=Arrays.stream(state.split(",",-1)).map(value->Objects.requireNonNull(value).trim()).distinct().toList();
        if(!allowed.containsAll(values)) throw new UserInputException("Estado inválido");
        where.append(" AND state ").append(exclude?"NOT IN":"IN").append(" (SELECT value FROM json_each(?))");
        args.add(JSON.writeValueAsString(values));
    }
}
