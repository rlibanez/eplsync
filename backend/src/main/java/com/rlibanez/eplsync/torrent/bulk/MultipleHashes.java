package com.rlibanez.eplsync.torrent.bulk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum MultipleHashes {
    ALL, SKIP, FIRST;

    @JsonValue
    public String value() { return name().toLowerCase(Locale.ROOT); }

    @JsonCreator
    public static MultipleHashes parse(String value) {
        if (value == null) return null;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("multipleHashes debe ser all, skip o first"); }
    }
}
