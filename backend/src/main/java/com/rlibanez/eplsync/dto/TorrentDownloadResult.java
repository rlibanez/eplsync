package com.rlibanez.eplsync.dto;

public record TorrentDownloadResult(Long eplId, String hash, String client, Status status) {
    public enum Status { ACCEPTED, ALREADY_EXISTS }
}
