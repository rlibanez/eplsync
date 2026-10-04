package com.rlibanez.eplsync.settings;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class SettingsSnapshotFilter extends OncePerRequestFilter {
    private final ServerSettings settings;
    public SettingsSnapshotFilter(ServerSettings settings) { this.settings = settings; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var scope = settings.pin();
        try { chain.doFilter(request, response); }
        finally { try { scope.close(); } catch (Exception ex) { throw new ServletException(ex); } }
    }
}
