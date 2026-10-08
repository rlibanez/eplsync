package com.rlibanez.eplsync.security;

import com.rlibanez.eplsync.dto.ErrorResponse;
import com.rlibanez.eplsync.exception.CatalogOperationException;
import com.rlibanez.eplsync.importer.CatalogOperationBudget;
import com.rlibanez.eplsync.service.CatalogOperationGate;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collection;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Reserve before CSRF/form parsing and MVC's multipart resolver can create temporary files. */
final class CatalogImportAdmissionFilter extends OncePerRequestFilter {
    private final CatalogOperationGate gate;
    private final java.util.function.BooleanSupplier requireHttps;
    private final JsonMapper json=JsonMapper.builder().build();
    CatalogImportAdmissionFilter(CatalogOperationGate gate) { this(gate,() -> false); }
    CatalogImportAdmissionFilter(CatalogOperationGate gate,java.util.function.BooleanSupplier requireHttps) {
        this.gate=gate; this.requireHttps=requireHttps;
    }
    private String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=path(request);
        return !path.startsWith("/api/catalog/import") || path.equals("/api/catalog/import/metadata");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        if(requireHttps.getAsBoolean() && !request.isSecure()) { SecurityConfiguration.error(request,response,400,"HTTPS_REQUIRED");return; }
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            SecurityConfiguration.error(request,response,401,"AUTH_REQUIRED"); return;
        }
        var permission=path(request).startsWith("/api/catalog/import/missing") ? Permission.CATALOG_DELETE : Permission.CATALOG_IMPORT;
        if(!Permission.has(permission)) { SecurityConfiguration.error(request,response,403,"ACCESS_DENIED");return; }
        try(var lease=gate.acquire()) {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public Collection<Part> getParts() throws IOException,ServletException {
                    CatalogOperationBudget.check();
                    var parts=super.getParts();
                    try { CatalogOperationBudget.check(); return parts; }
                    catch(CatalogOperationException ex) {
                        for(var part:parts) try { part.delete(); } catch(IOException cleanup) { ex.addSuppressed(cleanup); }
                        throw ex;
                    }
                }
                @Override public Part getPart(String name) throws IOException,ServletException {
                    return getParts().stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
                }
            },response);
        } catch(CatalogOperationException ex) {
            logger.warn(ex.getMessage());
            if(response.isCommitted()) throw ex;
            response.setStatus(ex.getStatus().value()); response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8"); response.setHeader("Cache-Control","no-store");
            response.getWriter().write(json.writeValueAsString(new ErrorResponse(ex.getStatus().value(),
                "Operación de catálogo rechazada",ex.getMessage(),request.getRequestURI())));
        }
    }
}
