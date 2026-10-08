package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.config.TorrentProperties;
import com.rlibanez.eplsync.dto.TorrentConnectionStatus;
import com.rlibanez.eplsync.service.TorrentClientService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TorrentClientServiceTests {
    @Test
    void selectsAnAdapterThroughTheCommonContract() {
        var config = new TorrentProperties();
        config.setEnabled(true);
        config.setClient("another-client");
        var selected = mock(TorrentClient.class);
        var other = mock(TorrentClient.class);
        when(selected.type()).thenReturn("another-client");
        when(other.type()).thenReturn("qbittorrent");
        var expected = new TorrentConnectionStatus(true, true, "another-client", "basic", "1.0", "1");
        when(selected.checkConnection()).thenReturn(expected);
        var service = new TorrentClientService(config, List.of(other, selected), org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.class));
        assertThat(service.checkConnection()).isEqualTo(expected);
        verify(other, never()).checkConnection();
    }

    @Test
    void rejectsUnsupportedAndAmbiguousClientsWhenEnabled() {
        var config = new TorrentProperties();
        config.setEnabled(true);
        assertThatThrownBy(() -> new TorrentClientService(config, List.of(), org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.class)))
                .isInstanceOf(IllegalArgumentException.class);
        var adapter = mock(TorrentClient.class);
        when(adapter.type()).thenReturn("qbittorrent");
        assertThatThrownBy(() -> new TorrentClientService(config, List.of(adapter, adapter), org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectionCheckWorksWhenDisabledButRequiresAnAdapter() {
        var config = new TorrentProperties();
        config.setClient("future-client");
        assertThatThrownBy(() -> new TorrentClientService(config, List.of(), mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.class)).checkConnection())
                .isInstanceOf(com.rlibanez.eplsync.exception.TorrentOperationException.class);
        var adapter = mock(TorrentClient.class);
        when(adapter.type()).thenReturn("future-client");
        new TorrentClientService(config, List.of(adapter), org.mockito.Mockito.mock(com.rlibanez.eplsync.torrent.downloads.DownloadTrackingService.class)).checkConnection();
        verify(adapter).checkConnection();
    }
}
