package com.rlibanez.eplsync.torrent.downloads;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name="torrent_download_files")
@lombok.Getter @lombok.Setter
public class DownloadFile {
    @jakarta.persistence.Id private String id;
    @jakarta.persistence.Column(name="download_id",nullable=false) private String downloadId;
    @jakarta.persistence.Column(name="file_index",nullable=false) private int index;
    @jakarta.persistence.Column(name="relative_path",nullable=false) private String path;
    @jakarta.persistence.Column(nullable=false) private long size;
    @jakarta.persistence.Column(nullable=false) private String extension;
}
