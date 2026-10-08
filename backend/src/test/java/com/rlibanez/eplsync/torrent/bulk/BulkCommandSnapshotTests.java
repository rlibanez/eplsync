package com.rlibanez.eplsync.torrent.bulk;

import com.rlibanez.eplsync.dto.TorrentDownloadRequest;
import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import com.rlibanez.eplsync.torrent.TorrentDownload;
import com.rlibanez.eplsync.torrent.TorrentNameResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BulkCommandSnapshotTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void excludesLargeCatalogFieldsAndPreservesExecutionData() {
        var book = CatalogBook.builder().eplId(12L).revision(1.2).title("Title")
                .author("Author").synopsis("x".repeat(200_000)).links("unused links")
                .coverUrl("https://example.org/cover").build();
        var command = new TorrentDownload("hash", "magnet", false, "/downloads", "Frozen name", null, book);
        var json = BulkCommandSnapshot.encode(command, mapper);
        assertThat(json).hasSizeLessThan(400).doesNotContain("synopsis", "links", "coverUrl", "author");
        var restored = mapper.readValue(json, TorrentDownload.class);
        assertThat(restored.hash()).isEqualTo(command.hash());
        assertThat(restored.magnet()).isEqualTo(command.magnet());
        assertThat(restored.name()).isEqualTo(command.name());
        assertThat(restored.savePath()).isEqualTo(command.savePath());
        assertThat(restored.start()).isFalse();
        assertThat(restored.book().getEplId()).isEqualTo(12L);
        assertThat(restored.book().getRevision()).isEqualTo(1.2);
        assertThat(restored.book().getTitle()).isEqualTo("Title");
    }

    @Test void preservesOnlyAdditionalFieldsUsedByTagsWithoutReinterpretingLiteralBraces() {
        var patterns = List.of("{author}", "{language}", "{collection}");
        var book = CatalogBook.builder().eplId(12L).revision(1.2).title("Title")
                .author("Author {title}").language(Language.INGLES).collection("Collection")
                .synopsis("discarded").build();
        var command = new TorrentDownload("hash", "magnet", true, null, null,
                new TorrentDownloadRequest.QBittorrent("category", patterns, true), book);
        var json = BulkCommandSnapshot.encode(command, mapper);
        book.setAuthor("Changed"); book.setLanguage(Language.ESPANOL);
        var restored = mapper.readValue(json, TorrentDownload.class);
        assertThat(new TorrentNameResolver().resolveTags(restored.qbittorrent().tags(), restored.book()))
                .containsExactly("Author {title}", "en", "Collection");
        assertThat(restored.qbittorrent().category()).isEqualTo("category");
        assertThat(restored.qbittorrent().autoManagement()).isTrue();
        assertThat(json).doesNotContain("synopsis");
    }
}
