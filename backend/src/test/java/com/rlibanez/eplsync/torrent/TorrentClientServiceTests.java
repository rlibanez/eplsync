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
        var service = new TorrentClientService(config, List.of(other, selected));
        assertThat(service.checkConnection()).isEqualTo(expected);
        verify(other, never()).checkConnection();
    }

    @Test
    void rejectsUnsupportedAndAmbiguousClientsWhenEnabled() {
        var config = new TorrentProperties();
        config.setEnabled(true);
        assertThatThrownBy(() -> new TorrentClientService(config, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var adapter = mock(TorrentClient.class);
        when(adapter.type()).thenReturn("qbittorrent");
        assertThatThrownBy(() -> new TorrentClientService(config, List.of(adapter, adapter)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledConnectionDoesNotNeedAnAdapterOrInvokeIt() {
        var config = new TorrentProperties();
        config.setClient("future-client");
        var status = new TorrentClientService(config, List.of()).checkConnection();
        assertThat(status.connected()).isFalse();
        assertThat(status.client()).isEqualTo("future-client");
        var adapter = mock(TorrentClient.class);
        when(adapter.type()).thenReturn("future-client");
        new TorrentClientService(config, List.of(adapter)).checkConnection();
        verify(adapter, never()).checkConnection();
    }
}
