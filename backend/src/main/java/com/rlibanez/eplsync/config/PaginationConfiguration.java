package com.rlibanez.eplsync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/** No aplicar el recorte predeterminado de Spring (2000) al tamaño solicitado. */
@Configuration(proxyBeanMethods = false)
public class PaginationConfiguration {
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageSizeCustomizer() {
        return resolver -> resolver.setMaxPageSize(Integer.MAX_VALUE);
    }
}
