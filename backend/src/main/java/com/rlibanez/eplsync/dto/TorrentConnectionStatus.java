package com.rlibanez.eplsync.dto;

public record TorrentConnectionStatus(
        boolean enabled, boolean connected, String client, String authMode, String version, String apiVersion) {
}
