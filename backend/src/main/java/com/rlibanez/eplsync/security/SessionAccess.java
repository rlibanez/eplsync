package com.rlibanez.eplsync.security;

import jakarta.servlet.http.*;
import java.time.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
public class SessionAccess {
    private final AccountStore accounts;
    private final RememberedAccess remembered;
    public SessionAccess(AccountStore accounts,RememberedAccess remembered) { this.accounts=accounts;this.remembered=remembered; }
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
    public void login(HttpServletRequest request,HttpServletResponse response,Account a) {login(request,response,a,false);}
    public void login(HttpServletRequest request,HttpServletResponse response,Account a,boolean keep) {
        remembered.revoke(request);
        if(remembered.cookie(request)!=null) remembered.clear(request,response);
        establish(request,response,a);
        if(keep) {
            var grant=remembered.issue(request,response,a);
            if(grant!=null) request.getSession().setAttribute(RememberedAccess.SESSION_ATTRIBUTE,grant.hash());
        }
    }
    public boolean rememberedSessionValid(HttpServletRequest request,Account account) {
        var session=request.getSession(false);
        if(session==null || !(session.getAttribute(RememberedAccess.SESSION_ATTRIBUTE) instanceof String digest)) return true;
        var grant=remembered.valid(digest);
        return grant!=null && grant.account().id().equals(account.id()) && grant.account().securityVersion()==account.securityVersion();
    }
    public boolean restore(HttpServletRequest request,HttpServletResponse response,Account previous) {
        var grant=remembered.authenticate(request,response);
        if(grant==null) return false;
        if(previous!=null && (!previous.id().equals(grant.account().id()) || previous.securityVersion()!=grant.account().securityVersion())) return false;
        establish(request,response,grant.account());
        request.getSession().setAttribute(RememberedAccess.SESSION_ATTRIBUTE,grant.hash());
        return true;
    }
    private void establish(HttpServletRequest request,HttpServletResponse response,Account a) {
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
        AuthenticationLog.login(a,request.getRemoteAddr());
    }
    public void logout(HttpServletRequest request,HttpServletResponse response) {logout(request);remembered.clear(request,response);}
    public void logout(HttpServletRequest request) {
        remembered.revoke(request);
        var session=request.getSession(false); if(session!=null) session.invalidate(); SecurityContextHolder.clearContext();
    }
}
