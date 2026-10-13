package com.rlibanez.eplsync.torrent.downloads;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EpubBatchesTests {
    @Test void isolatesSessionsAndConsumesLinksOnlyOnce() {
        var files=mock(EpubFiles.class);
        var plan=new EpubFiles.Plan("server","/downloads","/local",false,List.of());
        when(files.prepare(List.of("one"))).thenReturn(plan);
        var batches=new EpubBatches(files);
        var link=batches.prepare("owner",List.of("one"));
        var token=link.url().substring(link.url().lastIndexOf('/')+1);
        assertThatThrownBy(() -> batches.take("other",token)).hasMessageContaining("sesión");
        assertThat(batches.take("owner",token)).isSameAs(plan);
        assertThatThrownBy(() -> batches.take("owner",token)).hasMessageContaining("caducado");
    }
    @Test void boundsUnconsumedPreparationsPerSession() {
        var files=mock(EpubFiles.class);
        when(files.prepare(anyList())).thenReturn(new EpubFiles.Plan("server","/downloads","/local",false,List.of()));
        var batches=new EpubBatches(files);
        batches.prepare("owner",List.of("one"));batches.prepare("owner",List.of("two"));
        assertThatThrownBy(() -> batches.prepare("owner",List.of("three"))).hasMessageContaining("pendientes");
        verify(files,times(2)).prepare(anyList());
    }
}
