package com.rlibanez.eplsync.torrent.updates;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CleanupTiming {
    IMMEDIATE("immediate"), AFTER_DOWNLOAD("afterDownload");
    private final String value;
    CleanupTiming(String value) { this.value=value; }
    @JsonValue public String value() { return value; }
    @JsonCreator public static CleanupTiming parse(String value) {
        for (var timing:values()) if(timing.value.equals(value)) return timing;
        throw new com.rlibanez.eplsync.exception.UserInputException("cleanupTiming debe ser immediate o afterDownload");
    }
}
