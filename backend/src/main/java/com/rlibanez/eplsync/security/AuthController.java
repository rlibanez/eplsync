package com.rlibanez.eplsync.security;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@PreAuthorize("permitAll()")
public class AuthController {
    private final AccountStore accounts;
    private final SessionAccess sessions;
    private final LoginThrottle throttle;
    private final String initialAdminKey;
    public AuthController(AccountStore accounts,SessionAccess sessions,LoginThrottle throttle,
            @org.springframework.beans.factory.annotation.Value("${eplsync.security.initial-admin-key:}") String initialAdminKey) {
        this.accounts=accounts;this.sessions=sessions;this.throttle=throttle;this.initialAdminKey=initialAdminKey;
    }
    private boolean initialKeyRequired() { return !initialAdminKey.isBlank(); }
    private boolean initialKeyMatches(String supplied) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            return java.security.MessageDigest.isEqual(
                digest.digest(initialAdminKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                digest.digest((supplied==null ? "" : supplied).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable",ex); }
    }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/status") public Map<String,Object> status() {var p=accounts.policy();return Map.of("initialized",accounts.initialized(),"registrationEnabled",accounts.initialized()&&p.registrationEnabled(),"approvalRequired",p.approvalRequired(),"passwordMinimumLength",p.passwordMinimumLength(),"initialAdminKeyRequired",!accounts.initialized()&&initialKeyRequired());}
    public record Setup(@NotBlank @Size(min=3,max=64) String username,
            @NotBlank @jakarta.validation.constraints.Email @Size(max=254) String email,
            @NotBlank @Size(max=256) String password,
            @NotBlank @Size(max=256) String passwordConfirmation, String initialAdminKey) {}
    @PostMapping("/setup") public Account setup(@Valid @RequestBody Setup input,HttpServletRequest request,HttpServletResponse response) {
        throttle.check("setup:"+request.getRemoteAddr(),5);
        if(accounts.initialized()) throw new ResponseStatusException(HttpStatus.CONFLICT,"La instalación ya está inicializada");
        if(initialKeyRequired() && !initialKeyMatches(input.initialAdminKey()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Clave de configuración inicial incorrecta");
        var account=accounts.initialize(input.username(),input.email(),input.password(),input.passwordConfirmation());
        sessions.login(request,response,account);
        return account;
    }
    public record Login(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=256) String password) {
        public Login { if (username != null) username = username.strip(); }
    }
    @PostMapping("/login") public Account login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        throttle.check("login:"+request.getRemoteAddr(),30);
        throttle.check("account:"+input.username().toLowerCase(Locale.ROOT),10);
        var account=accounts.authenticate(input.username(),input.password());
        if(account==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Usuario o contraseña incorrectos, cuenta no activa o contraseña temporal caducada");
        sessions.login(request,response,account);return account;
    }
    public record Register(@NotBlank String username,@NotBlank @jakarta.validation.constraints.Email String email,@NotBlank @Size(max=256) String password) {}
    @PostMapping("/register") public Map<String,String> register(@Valid @RequestBody Register input,HttpServletRequest request) {
        throttle.check("register:"+request.getRemoteAddr(),5);
        String status=accounts.register(input.username(),input.email(),input.password());
        return Map.of("status",status);
    }
    @GetMapping("/me") @PreAuthorize("isAuthenticated()") public Account me() { var previous=sessions.current(); var a=accounts.find(previous.id());
        return new Account(a.id(),a.username(),a.email(),a.role(),a.status(),a.mustChangePassword(),a.temporaryExpiresAt(),a.securityVersion(),a.permissions(),previous.authenticatedAt()); }
    @PostMapping("/logout") @PreAuthorize("isAuthenticated()") public Map<String,Boolean> logout(HttpServletRequest request) { sessions.logout(request);return Map.of("success",true); }
    public record Password(@NotBlank @Size(max=256) String currentPassword,@NotBlank @Size(max=256) String newPassword) {}
    @PostMapping("/password") @PreAuthorize("isAuthenticated()") public Map<String,Boolean> password(@Valid @RequestBody Password input,HttpServletRequest request) {
        throttle.check("password:"+sessions.current().id(),10);
        accounts.changePassword(sessions.current(),input.currentPassword(),input.newPassword());sessions.logout(request);
        return Map.of("success",true);
    }
    public record Email(@NotBlank @jakarta.validation.constraints.Email String email) {}
    @PutMapping("/email") @PreAuthorize("isAuthenticated()") public Map<String,Boolean> email(@Valid @RequestBody Email input) {accounts.email(sessions.current(),input.email());return Map.of("success",true);}
}
