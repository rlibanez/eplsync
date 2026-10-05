package com.rlibanez.eplsync.torrent.downloads;

import com.rlibanez.eplsync.model.CatalogBook;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogDownloadViewTests {
    @org.junit.jupiter.api.BeforeEach void authorizeHistory() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("test",null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("BOOK_HISTORY_READ"))));
    }
    @org.junit.jupiter.api.AfterEach void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void groupsQueriesForLargeCatalogResultsAndOrdersHistory() {
        var repository = mock(DownloadRepository.class);
        var rows = List.of(row(1.0, "2026-01-01T00:00:00Z"), row(2.0, "2026-01-01T00:00:00Z"),
                row(2.0, "2026-01-02T00:00:00Z"));
        when(repository.findByEplIdIn(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            assertThat(ids.size()).isLessThanOrEqualTo(500);
            return ids.contains(1L) ? rows : List.of();
        });
        var books = LongStream.rangeClosed(1, 501).mapToObj(id -> CatalogBook.builder().eplId(id).build()).toList();
        var response = new CatalogDownloadViewService(repository).enrich(books);
        verify(repository, times(2)).findByEplIdIn(any());
        assertThat(response).hasSize(501);
        assertThat(response.getFirst().download().items()).extracting(item -> item.id())
                .containsExactly(rows.get(2).getId(), rows.get(1).getId(), rows.get(0).getId());
        assertThat(response.getLast().download().items()).isEmpty();
    }
    private DownloadRecord row(double revision, String createdAt) {
        var row = new DownloadRecord(); row.setEplId(1L); row.setRevision(revision);
        row.setCreatedAt(Instant.parse(createdAt)); row.setStatus(DownloadStatus.SUBMITTED); return row;
    }
}
