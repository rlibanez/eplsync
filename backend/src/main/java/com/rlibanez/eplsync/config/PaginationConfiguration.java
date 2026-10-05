package com.rlibanez.eplsync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/** Controller validation rejects oversized requests instead of silently accepting them. */
@Configuration(proxyBeanMethods = false)
public class PaginationConfiguration {
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageSizeCustomizer() {
        return resolver -> resolver.setMaxPageSize(QueryLimits.MAX_SIZE);
    }
}
