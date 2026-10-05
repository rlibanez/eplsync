package com.rlibanez.eplsync.security;

import jakarta.servlet.http.*;
import java.time.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
public class SessionAccess {
    private final AccountStore accounts;
    public SessionAccess(AccountStore accounts) { this.accounts=accounts; }
    public Account current() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof Account a)) throw new org.springframework.security.access.AccessDeniedException("Se requiere una sesión");
        return a;
    }
    public boolean valid(Account a) {
        var current=accounts.find(a.id());
        return current!=null && current.status().equals("ACTIVE") && current.securityVersion()==a.securityVersion()
            && a.authenticatedAt()!=null && a.authenticatedAt().plus(Duration.ofHours(accounts.policy().maximumHours())).isAfter(Instant.now())
            && (current.temporaryExpiresAt()==null || current.temporaryExpiresAt().isAfter(Instant.now()));
    }
    public void login(HttpServletRequest request,HttpServletResponse response,Account a) {
        var old=request.getSession(false); if(old!=null) old.invalidate();
        var session=request.getSession(true); session.setMaxInactiveInterval(accounts.policy().idleMinutes()*60);
        var authorities=new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
        if(!a.mustChangePassword()) {
            a.permissions().forEach(p->authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(p.name())));
            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+a.role()));
        }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(a,null,authorities));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
    }
    public void logout(HttpServletRequest request) {
        var session=request.getSession(false); if(session!=null) session.invalidate(); SecurityContextHolder.clearContext();
    }
}
