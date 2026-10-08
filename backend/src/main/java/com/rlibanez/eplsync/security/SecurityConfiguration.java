package com.rlibanez.eplsync.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

@Configuration(proxyBeanMethods=false)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean org.springframework.security.core.userdetails.UserDetailsService noImplicitUsers() {
        return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("No implicit accounts"); };
    }
    @Bean PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("argon2id",java.util.Map.of("argon2id",new Argon2PasswordEncoder(16,32,1,19456,2)));
    }
    @Bean @ConditionalOnWebApplication SecurityFilterChain security(HttpSecurity http,SessionAccess sessions,AccountStore accounts,
            org.springframework.core.env.Environment env,com.rlibanez.eplsync.service.CatalogOperationGate catalogOperations) throws Exception {
        http.formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable()).requestCache(c->c.disable());
        http.csrf(c->c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()));
        http.authorizeHttpRequests(c->c
            .dispatcherTypeMatchers(DispatcherType.ERROR,DispatcherType.FORWARD,DispatcherType.ASYNC).permitAll()
            .requestMatchers("/api/auth/csrf","/api/auth/status","/api/auth/setup","/api/auth/login","/api/auth/register","/actuator/health").permitAll()
            .requestMatchers("/api/**").authenticated()
            .requestMatchers("/actuator/**").denyAll()
            .anyRequest().permitAll());
        http.exceptionHandling(c->c.authenticationEntryPoint((req,res,ex)->error(req,res,401,"AUTH_REQUIRED"))
            .accessDeniedHandler((req,res,ex)->error(req,res,403,ex instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED")));
        // Admission precedes database-backed session checks: a busy SQLite connection must not queue another import.
        http.addFilterAfter(new CatalogImportAdmissionFilter(catalogOperations,
            () -> env.getProperty("eplsync.security.require-https",Boolean.class,false)),SecurityContextHolderFilter.class);
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
                if(req.getServletPath().startsWith("/api/")) res.setHeader("Cache-Control","no-store");
                boolean requireHttps=env.getProperty("eplsync.security.require-https",Boolean.class,false);
                if(requireHttps && !req.isSecure()) { error(req,res,400,"HTTPS_REQUIRED"); return; }
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth!=null && auth.getPrincipal() instanceof Account a) {
                    if(!sessions.valid(a)) { sessions.logout(req); error(req,res,401,"SESSION_EXPIRED");return; }
                    var session=req.getSession(false);
                    if(session!=null) session.setMaxInactiveInterval(accounts.policy().idleMinutes()*60);
                    if(a.mustChangePassword() && req.getServletPath().startsWith("/api/")
                        && !java.util.Set.of("/api/auth/me","/api/auth/password","/api/auth/logout","/api/auth/csrf","/api/auth/status").contains(req.getServletPath())) {
                        error(req,res,403,"PASSWORD_CHANGE_REQUIRED");return;
                    }
                }
                chain.doFilter(req,res);
            }
        },CatalogImportAdmissionFilter.class);
        return http.build();
    }
    static void error(HttpServletRequest req,HttpServletResponse res,int status,String code) throws IOException {
        BrowserErrorResponse.write(req,res,status,code);
    }
    /** New API handlers without an explicit method/class policy are inaccessible. */
    @Bean @ConditionalOnWebApplication WebMvcConfigurer explicitPolicies() {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
                    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception {
                        if(handler instanceof HandlerMethod method
                            && !AnnotatedElementUtils.hasAnnotation(method.getMethod(),PreAuthorize.class)
                            && !AnnotatedElementUtils.hasAnnotation(method.getBeanType(),PreAuthorize.class)) {
                            error(req,res,403,"NO_ACCESS_POLICY");return false;
                        }
                        return true;
                    }
                }).addPathPatterns("/api/**");
            }
        };
    }
}
