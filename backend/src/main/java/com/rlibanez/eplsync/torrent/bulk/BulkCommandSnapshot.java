package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.torrent.TorrentDownload;
import com.rlibanez.eplsync.torrent.TorrentNameResolver;
import org.springframework.beans.BeanWrapperImpl;
import tools.jackson.databind.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;

/** Only execution and tracking data; book fields used by tag templates are frozen too. */
record BulkCommandSnapshot(String hash, String magnet, boolean start, String savePath,
        String name, TorrentDownloadRequest.QBittorrent qbittorrent, Map<String, Object> book) {
    static String encode(TorrentDownload command, JsonMapper mapper) {
        var fields = new LinkedHashSet<>(List.of("eplId", "revision", "title"));
        if (command.qbittorrent() != null)
            fields.addAll(TorrentNameResolver.tagFields(command.qbittorrent().tags()));
        var source = new BeanWrapperImpl(command.book());
        var book = new LinkedHashMap<String, Object>();
        for (String field : fields) book.put(field, source.getPropertyValue(field));
        return mapper.writeValueAsString(new BulkCommandSnapshot(command.hash(), command.magnet(),
                command.start(), command.savePath(), command.name(), command.qbittorrent(), book));
    }
}
