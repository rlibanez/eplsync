package com.rlibanez.eplsync.torrent.updates;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PreviousVersions {
    KEEP("keep"), REMOVE_TORRENT("removeTorrent"), REMOVE_TORRENT_AND_FILES("removeTorrentAndFiles");
    private final String value;
    PreviousVersions(String value) { this.value = value; }
    @JsonValue public String value() { return value; }
    @JsonCreator public static PreviousVersions parse(String value) {
        for (var policy : values()) if (policy.value.equals(value)) return policy;
        throw new IllegalArgumentException("previousVersions debe ser keep, removeTorrent o removeTorrentAndFiles");
    }
}
