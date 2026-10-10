package com.rlibanez.eplsync.config;

import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;

/** Bound running work and briefly absorb read bursts without an unbounded queue. */
@Configuration
public class QueryConcurrencyConfiguration implements WebMvcConfigurer {
    private final Semaphore queries = new Semaphore(4, true);
    private final Semaphore waiting = new Semaphore(8);
    @Bean public ThreadPoolTaskExecutor exportStreamingExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("eplsync-export-"); executor.setDaemon(true);
        return executor;
    }
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(exportStreamingExecutor());
    }
    @Bean public HandlerInterceptor boundedQueries() {
        return new HandlerInterceptor() {
            private static final String LEASE = QueryConcurrencyConfiguration.class.getName();
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                boolean acquired = queries.tryAcquire();
                if (!acquired && "GET".equals(request.getMethod()) && waiting.tryAcquire()) {
                    try { acquired = queries.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    finally { waiting.release(); }
                }
                if (!acquired) {
                    response.setHeader("Retry-After", "1");
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                            "Hay demasiadas consultas en curso. Inténtalo de nuevo en unos segundos.");
                }
                request.setAttribute(LEASE, Boolean.TRUE); return true;
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                if (Boolean.TRUE.equals(request.getAttribute(LEASE))) { request.removeAttribute(LEASE); queries.release(); }
            }
        };
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(boundedQueries()).addPathPatterns("/api/home/summary", "/api/catalog/books", "/api/catalog/books/*/history", "/api/catalog/magnets", "/api/catalog/directory/**",
                "/api/torrent/revision-updates/search", "/api/torrent/revision-updates/send", "/api/torrent/downloads", "/api/torrent/downloads/summary", "/api/torrent/downloads/refresh-selected", "/api/torrent/downloads/remove-selected", "/api/torrent/downloads/delete-history", "/api/torrent/downloads/remove-records", "/api/torrent/jobs", "/api/torrent/jobs/*/items",
                "/api/events", "/api/events/operations", "/api/torrent/books", "/api/torrent/updates", "/api/torrent/refresh");
    }
}
