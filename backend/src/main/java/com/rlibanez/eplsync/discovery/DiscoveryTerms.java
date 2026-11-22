package com.rlibanez.eplsync.discovery;

import java.sql.*;
import java.util.*;
import com.rlibanez.eplsync.ordering.UnicodeText;
import tools.jackson.databind.json.JsonMapper;

/** Normalize whole values, splitting coauthors by & and genres by comma, as in Directory. */
public final class DiscoveryTerms {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private DiscoveryTerms() {}
    public static String key(String value) {return UnicodeText.lower(value.strip().replaceAll("(?U)\\s+"," "));}
    public static String field(String criterion) {
        return switch(criterion) {case "AUTHOR"->"author";case "COLLECTION"->"collection";case "GENRE"->"genres";case "LANGUAGE"->"language";case "YEAR"->"publicationYear";default->throw new IllegalArgumentException("Invalid discovery criterion");};
    }
    public static String column(String criterion) {return criterion.equals("YEAR")?"publication_year":field(criterion);}
    public static List<String> labels(String value,String criterion) {
        if(value==null || value.isBlank()) return List.of();
        if(criterion.equals("LANGUAGE")) value=com.rlibanez.eplsync.model.enums.Language.fromApiValue(value).getIsoCode();
        if(criterion.equals("YEAR") && (!value.matches("[0-9]{1,4}") || Integer.parseInt(value)<1)) return List.of();
        return Arrays.stream(criterion.equals("AUTHOR")?value.split("&"):criterion.equals("GENRE")?value.split(","):new String[]{value}).map(String::strip).filter(s->!s.isEmpty()).distinct().toList();
    }
    public static List<String> keys(String value,String criterion) {return labels(value,criterion).stream().map(DiscoveryTerms::key).distinct().toList();}
    public static void register(Connection connection) throws SQLException {
        org.sqlite.Function.create(connection,"epl_discovery_tokens",new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {result(JSON.writeValueAsString(keys(value_text(0),Objects.toString(value_text(1),"AUTHOR"))));}
        });
    }
}
