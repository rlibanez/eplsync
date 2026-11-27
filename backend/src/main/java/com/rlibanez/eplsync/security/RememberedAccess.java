package com.rlibanez.eplsync.security;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Fixed-expiry device credentials. Only SHA-256 digests of random 256-bit secrets are persisted. */
@Service
public class RememberedAccess {
    static final String COOKIE="EPLSYNC_REMEMBER";
    static final String SESSION_ATTRIBUTE=RememberedAccess.class.getName()+".credential";
    public record Grant(String hash,Account account) {}
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final boolean requireHttps;
    public RememberedAccess(JdbcTemplate jdbc,AccountStore accounts,org.springframework.core.env.Environment environment) {
        this.jdbc=jdbc;this.accounts=accounts;requireHttps=environment.getProperty("eplsync.security.require-https",Boolean.class,false);
    }
    static String hash(String token) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    String cookie(HttpServletRequest request) {
        if(request.getCookies()==null) return null;
        String value=null;
        for(var c:request.getCookies()) if(c.getName().equals(COOKIE)) {
            if(value!=null) return ""; // Do not choose between conflicting credentials.
            value=c.getValue();
        }
        return value;
    }
    private void cookie(HttpServletRequest request,HttpServletResponse response,String value,long seconds) {
        response.addHeader("Set-Cookie",org.springframework.http.ResponseCookie.from(COOKIE,value).httpOnly(true)
            .secure(requireHttps || request.isSecure()).sameSite("Lax").path("/").maxAge(seconds).build().toString());
    }
    void clear(HttpServletRequest request,HttpServletResponse response) {cookie(request,response,"",0);}
    Grant issue(HttpServletRequest request,HttpServletResponse response,Account account) {
        if(account.mustChangePassword() || account.temporaryExpiresAt()!=null) return null;
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),digest=hash(token);
        long now=Instant.now().toEpochMilli(),duration=Duration.ofDays(accounts.policy().rememberMeDays()).toMillis();
        jdbc.update("DELETE FROM remembered_access WHERE expires_at<=?",now);
        jdbc.update("INSERT INTO remembered_access(token_hash,user_id,security_version,issued_at,expires_at) VALUES(?,?,?,?,?)",digest,account.id(),account.securityVersion(),now,now+duration);
        cookie(request,response,token,duration/1000);
        return new Grant(digest,account);
    }
    Grant valid(String digest) {
        if(digest==null || !digest.matches("[a-f0-9]{64}")) return null;
        var rows=jdbc.queryForList("SELECT * FROM remembered_access WHERE token_hash=?",digest);
        if(rows.size()!=1) return null;
        var row=rows.getFirst();var a=accounts.find(row.get("user_id").toString());long now=Instant.now().toEpochMilli();
        long issued=((Number)row.get("issued_at")).longValue(),expiry=((Number)row.get("expires_at")).longValue();
        if(a==null || !a.status().equals("ACTIVE") || a.mustChangePassword() || a.temporaryExpiresAt()!=null ||
                a.securityVersion()!=((Number)row.get("security_version")).longValue() || now>=expiry ||
                now>=issued+Duration.ofDays(accounts.policy().rememberMeDays()).toMillis()) {
            jdbc.update("DELETE FROM remembered_access WHERE token_hash=?",digest);return null;
        }
        return new Grant(digest,a.authenticatedNow());
    }
    Grant authenticate(HttpServletRequest request,HttpServletResponse response) {
        String token=cookie(request);if(token==null) return null;
        var grant=token.matches("[A-Za-z0-9_-]{43}")?valid(hash(token)):null;
        if(grant==null) clear(request,response);
        return grant;
    }
    void revoke(HttpServletRequest request) {
        var session=request.getSession(false);
        if(session!=null && session.getAttribute(SESSION_ATTRIBUTE) instanceof String digest) jdbc.update("DELETE FROM remembered_access WHERE token_hash=?",digest);
        String token=cookie(request);
        if(token!=null && token.matches("[A-Za-z0-9_-]{43}")) jdbc.update("DELETE FROM remembered_access WHERE token_hash=?",hash(token));
    }
}
