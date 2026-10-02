package com.rlibanez.eplsync.maintenance;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MaintenanceFilter extends OncePerRequestFilter {
    private final MaintenanceGate gate;
    public MaintenanceFilter(MaintenanceGate gate) { this.gate = gate; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean reset = request.getMethod().equals("POST")
                && (request.getServletPath().equals("/api/maintenance/reset")
                    || request.getServletPath().equals("/api/catalog/import/missing/delete"));
        if (!gate.enter(reset)) {
            response.setStatus(409);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"MAINTENANCE_BUSY\"}");
            return;
        }
        try { chain.doFilter(request, response); }
        finally { gate.leave(reset); }
    }
}
