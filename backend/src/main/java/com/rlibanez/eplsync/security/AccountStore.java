package com.rlibanez.eplsync.security;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
@org.springframework.context.annotation.DependsOn("entityManagerFactory")
public class AccountStore {
    private final com.rlibanez.eplsync.events.EventJournal journal;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String dummyHash;
    private final int defaultPasswordMinimumLength;
    public record UserView(String id, String username, String email, String emailVerifiedAt, String role,
            String status, boolean mustChangePassword, String temporaryPasswordExpiresAt,
            String createdAt, String approvedAt, String approvedBy, Map<String,String> overrides, Set<Permission> permissions) {}
    public record Policy(boolean registrationEnabled, boolean approvalRequired, int idleMinutes, int maximumHours, int passwordMinimumLength) {
        public Policy(boolean registrationEnabled, boolean approvalRequired, int idleMinutes, int maximumHours) {
            this(registrationEnabled, approvalRequired, idleMinutes, maximumHours, 8);
        }
    }
    public record Temporary(UserView user, String password, Instant expiresAt) {}

    public AccountStore(JdbcTemplate jdbc, PasswordEncoder encoder, org.springframework.core.env.Environment environment, com.rlibanez.eplsync.events.EventJournal journal) {
        this.journal=journal;
        this.jdbc = jdbc; this.encoder = encoder;
        defaultPasswordMinimumLength = environment.getProperty("eplsync.security.password-min-length", Integer.class, 8);
        if (defaultPasswordMinimumLength < 8 || defaultPasswordMinimumLength > 128)
            throw new com.rlibanez.eplsync.exception.UserInputException("La longitud mínima de contraseña debe estar entre 8 y 128");
        dummyHash = encoder.encode(UUID.randomUUID().toString());
        jdbc.update("INSERT OR IGNORE INTO security_policy(id,password_minimum_length) VALUES(1,?)", defaultPasswordMinimumLength);
    }
    private void audit(String action, String id, String username, String actor) {
        var details=new LinkedHashMap<String,Object>();
        details.put("userId",id); details.put("username",username);
        if(actor==null) {
            var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if(authentication!=null && authentication.getPrincipal() instanceof Account account) actor=account.id();
        }
        if(actor!=null) details.put("actorId",actor);
        var eventActor = com.rlibanez.eplsync.events.EventContext.actor();
        if (actor != null && !"USER".equals(eventActor.kind())) {
            var names = jdbc.queryForList("SELECT username FROM users WHERE id=?", String.class, actor);
            eventActor = new com.rlibanez.eplsync.events.EventContext.Actor(actor, names.isEmpty() ? null : names.getFirst(), "USER");
        } else if (actor == null && action.equals("USER_CREATE") && !"USER".equals(eventActor.kind())) {
            eventActor = new com.rlibanez.eplsync.events.EventContext.Actor(id, username, "USER");
        }
        journal.recordAs(eventActor, com.rlibanez.eplsync.events.EventJournal.Category.SECURITY,action,
            com.rlibanez.eplsync.events.EventJournal.Outcome.SUCCEEDED,com.rlibanez.eplsync.events.EventContext.origin(),UUID.randomUUID().toString(),details);
    }
    public boolean initialized() { return jdbc.queryForObject("SELECT count(*) FROM users", Long.class) > 0; }
    public Policy policy() {
        return jdbc.queryForObject("SELECT * FROM security_policy WHERE id=1", (r,n) ->
            new Policy(r.getBoolean("registration_enabled"), r.getBoolean("approval_required"), r.getInt("idle_minutes"), r.getInt("maximum_hours"), r.getInt("password_minimum_length")));
    }
    @Transactional public void policy(Policy policy) {
        if (policy.idleMinutes() < 5 || policy.idleMinutes() > 1440 || policy.maximumHours() < 1 || policy.maximumHours() > 168)
            throw new com.rlibanez.eplsync.exception.UserInputException("Límites de sesión inválidos");
        if (policy.passwordMinimumLength() < 8 || policy.passwordMinimumLength() > 128)
            throw new com.rlibanez.eplsync.exception.UserInputException("La longitud mínima de contraseña debe estar entre 8 y 128");
        jdbc.update("UPDATE security_policy SET registration_enabled=?,approval_required=?,idle_minutes=?,maximum_hours=?,password_minimum_length=? WHERE id=1",
            policy.registrationEnabled(),policy.approvalRequired(),policy.idleMinutes(),policy.maximumHours(),policy.passwordMinimumLength());
    }
    /** Acquire SQLite's write lock before checking invariants, including across CLI processes. */
    private void lockMutations() { jdbc.update("UPDATE security_policy SET id=id WHERE id=1"); }
    private static String normalized(String username) { return username.toLowerCase(Locale.ROOT); }
    private static void identity(String username, String email) {
        if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"))
            throw new com.rlibanez.eplsync.exception.UserInputException("El usuario debe tener entre 3 y 64 caracteres: letras, números, punto, guion o guion bajo");
        if (email == null || email.length() > 254 || !email.equals(email.strip()) || email.chars().anyMatch(Character::isISOControl)
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new com.rlibanez.eplsync.exception.UserInputException("Email inválido");
    }
    private void password(String password) {
        int minimum = policy().passwordMinimumLength();
        if (password == null || password.length() < minimum || password.length() > 256 || password.isBlank())
            throw new com.rlibanez.eplsync.exception.UserInputException("La contraseña debe tener entre " + minimum + " y 256 caracteres");
    }
    public Account find(String id) {
        var accounts = jdbc.query("SELECT * FROM users WHERE id=?", (r,n) -> {
            String expiry = r.getString("temporary_password_expires_at");
            return new Account(r.getString("id"),r.getString("username"),r.getString("email"),r.getString("role"),r.getString("status"),
                r.getBoolean("must_change_password"),expiry == null ? null : Instant.parse(expiry),r.getLong("security_version"),Set.of(),null);
        },id);
        if (accounts.isEmpty()) return null;
        var a = accounts.getFirst(); var permissions = Permission.defaults(a.role());
        if (!a.role().equals("ADMIN")) jdbc.query("SELECT permission,effect FROM user_permission_overrides WHERE user_id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                var p = Permission.valueOf(r.getString(1));
                if (r.getString(2).equals("DENY")) permissions.remove(p); else permissions.add(p);
            },id);
        return new Account(a.id(),a.username(),a.email(),a.role(),a.status(),a.mustChangePassword(),a.temporaryExpiresAt(),a.securityVersion(),Set.copyOf(permissions),null);
    }
    public Account authenticate(String username, String password) {
        if (username == null || username.length() > 64 || password == null || password.length() > 256) return null;
        var rows = jdbc.queryForList("SELECT id,password_hash,security_version FROM users WHERE username_normalized=?",normalized(username));
        String hash = rows.isEmpty() ? dummyHash : (String) rows.getFirst().get("password_hash");
        boolean matches = encoder.matches(password,hash);
        if (!matches || rows.isEmpty()) return null;
        var a = find((String)rows.getFirst().get("id"));
        if (a == null || a.securityVersion()!=((Number)rows.getFirst().get("security_version")).longValue() || !a.status().equals("ACTIVE") || (a.temporaryExpiresAt()!=null && !a.temporaryExpiresAt().isAfter(Instant.now()))) return null;
        return a.authenticatedNow();
    }
    private String insert(String username,String email,String password,String role,String status,boolean temporary,Instant expiry,String actor) {
        identity(username,email); password(password);
        if (!Set.of("ADMIN","USER").contains(role)) throw new com.rlibanez.eplsync.exception.UserInputException("Rol inválido");
        // Callers hold SQLite's write lock, so this check and insert cannot race.
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE username_normalized=?", Long.class, normalized(username)) > 0)
            throw new com.rlibanez.eplsync.exception.UserInputException("El nombre de usuario no está disponible");
        String id=UUID.randomUUID().toString(), now=Instant.now().toString();
        try {
            jdbc.update("""
              INSERT INTO users(id,username,username_normalized,email,password_hash,role,status,must_change_password,
                temporary_password_expires_at,created_at,updated_at,approved_at,approved_by)
              VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
              """,id,username,normalized(username),email,encoder.encode(password),role,status,temporary,
                expiry==null?null:expiry.toString(),now,now,status.equals("ACTIVE")?now:null,actor);
        } catch (org.springframework.dao.DuplicateKeyException ex) { throw new com.rlibanez.eplsync.exception.UserInputException("El nombre de usuario no está disponible"); }
        audit("USER_CREATE",id,username,actor);
        return id;
    }
    @Transactional public String register(String username,String email,String password) {
        lockMutations();
        var policy=policy();
        if (!initialized() || !policy.registrationEnabled()) throw new org.springframework.security.access.AccessDeniedException("Registro cerrado");
        String status=policy.approvalRequired()?"PENDING":"ACTIVE";
        insert(username,email,password,"USER",status,false,null,null);
        return status;
    }
    /** The SQLite write lock makes claiming an empty installation atomic, also across processes. */
    @Transactional public Account initialize(String username,String email,String password,String passwordConfirmation) {
        lockMutations();
        if (initialized()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"La instalación ya está inicializada");
        if (!Objects.equals(password,passwordConfirmation)) throw new com.rlibanez.eplsync.exception.UserInputException("Las contraseñas no coinciden");
        String id=insert(username,email,password,"ADMIN","ACTIVE",false,null,null);
        return find(id).authenticatedNow();
    }
    @Transactional public Temporary create(String username,String email,String role,String actor) {
        lockMutations();
        if (!initialized()) throw new com.rlibanez.eplsync.exception.UserInputException("Crea primero el administrador desde la aplicación web");
        String secret=randomPassword(); Instant expiry=Instant.now().plusSeconds(86400);
        String id=insert(username,email,secret,role,"ACTIVE",true,expiry,actor);
        return new Temporary(view(id),secret,expiry);
    }
    @Transactional public Temporary recover(String identifier,boolean terminal) {
        lockMutations();
        boolean byEmail=terminal && identifier.contains("@");
        var ids=byEmail ? jdbc.queryForList("SELECT id,email FROM users WHERE role='ADMIN'").stream()
            .filter(row -> ((String)row.get("email")).equalsIgnoreCase(identifier)).map(row -> (String)row.get("id")).toList()
            : jdbc.queryForList("SELECT id FROM users WHERE username_normalized=?",String.class,normalized(identifier));
        if(ids.size()>1) throw new com.rlibanez.eplsync.exception.UserInputException("El email corresponde a varios administradores; utiliza el nombre de usuario");
        if(ids.isEmpty()) throw new com.rlibanez.eplsync.exception.UserInputException("Usuario inexistente");
        String id=ids.getFirst(); var a=find(id);
        if(terminal && !a.role().equals("ADMIN")) throw new com.rlibanez.eplsync.exception.UserInputException("La herramienta de emergencia solo recupera administradores");
        if(!a.status().equals("ACTIVE")) throw new com.rlibanez.eplsync.exception.UserInputException("La cuenta no está activa");
        String secret=randomPassword(); Instant expiry=Instant.now().plusSeconds(terminal?300:86400);
        jdbc.update("UPDATE users SET password_hash=?,must_change_password=1,temporary_password_expires_at=?,security_version=security_version+1,updated_at=? WHERE id=?",
            encoder.encode(secret),expiry.toString(),Instant.now().toString(),id);
        audit("USER_PASSWORD_RESET",id,a.username(),null);
        return new Temporary(view(id),secret,expiry);
    }
    @Transactional public void changePassword(Account actor,String oldPassword,String newPassword) {
        lockMutations();
        password(newPassword);
        var a=authenticate(actor.username(),oldPassword);
        if(a==null || a.securityVersion()!=actor.securityVersion()) throw new com.rlibanez.eplsync.exception.UserInputException("La contraseña actual no es válida");
        if(oldPassword.equals(newPassword)) throw new com.rlibanez.eplsync.exception.UserInputException("Elige una contraseña diferente");
        int changed=jdbc.update("UPDATE users SET password_hash=?,must_change_password=0,temporary_password_expires_at=NULL,password_changed_at=?,updated_at=?,security_version=security_version+1 WHERE id=? AND security_version=?",
            encoder.encode(newPassword),Instant.now().toString(),Instant.now().toString(),actor.id(),actor.securityVersion());
        if(changed!=1) throw new org.springframework.security.access.AccessDeniedException("La cuenta ha cambiado");
    }
    @Transactional public void email(Account actor,String email) {
        lockMutations();
        identity(actor.username(),email);
        jdbc.update("UPDATE users SET email=?,email_verified_at=NULL,updated_at=? WHERE id=? AND email<>?",email,Instant.now().toString(),actor.id(),email);
    }
    @Transactional public void update(String id,String role,String status,Map<String,String> overrides,String actor) {
        lockMutations();
        if(!Set.of("ADMIN","USER").contains(role)||!Set.of("ACTIVE","PENDING","DISABLED","REJECTED").contains(status)) throw new com.rlibanez.eplsync.exception.UserInputException("Rol o estado inválido");
        var previous=find(id); if(previous==null) throw new com.rlibanez.eplsync.exception.UserInputException("Usuario inexistente");
        if(previous.role().equals("ADMIN") && previous.status().equals("ACTIVE") && (!role.equals("ADMIN")||!status.equals("ACTIVE"))
                && jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'",Long.class)<=1)
            throw new com.rlibanez.eplsync.exception.UserInputException("Debe permanecer al menos un administrador activo");
        if(overrides==null || overrides.size()>Permission.values().length || (role.equals("ADMIN")&&!overrides.isEmpty()))
            throw new com.rlibanez.eplsync.exception.UserInputException("Excepciones de permisos inválidas");
        overrides.forEach((key,value)-> { Permission.valueOf(key); if(!Set.of("ALLOW","DENY").contains(value)) throw new com.rlibanez.eplsync.exception.UserInputException("Excepción inválida"); });
        jdbc.update("UPDATE users SET role=?,status=?,security_version=security_version+1,updated_at=?,approved_at=CASE WHEN ?='ACTIVE' AND approved_at IS NULL THEN ? ELSE approved_at END,approved_by=CASE WHEN ?='ACTIVE' AND approved_at IS NULL THEN ? ELSE approved_by END WHERE id=?",
            role,status,Instant.now().toString(),status,Instant.now().toString(),status,actor,id);
        jdbc.update("DELETE FROM user_permission_overrides WHERE user_id=?",id);
        overrides.forEach((key,value)->jdbc.update("INSERT INTO user_permission_overrides(user_id,permission,effect) VALUES(?,?,?)",id,key,value));
        audit("USER_UPDATE",id,previous.username(),actor);
    }
    @Transactional public void approve(String id,String actor) {
        lockMutations();
        var user=find(id);
        if(user==null) throw new com.rlibanez.eplsync.exception.UserInputException("Usuario inexistente");
        if(!user.status().equals("PENDING")) throw new com.rlibanez.eplsync.exception.UserInputException("La cuenta no está pendiente de aprobación");
        update(id,user.role(),"ACTIVE",view(id).overrides(),actor);
    }
    @Transactional public void delete(String id) {
        lockMutations();
        var user=find(id);
        if(user==null) throw new com.rlibanez.eplsync.exception.UserInputException("Usuario inexistente");
        if(user.role().equals("ADMIN") && user.status().equals("ACTIVE")
                && jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'",Long.class)<=1)
            throw new com.rlibanez.eplsync.exception.UserInputException("Debe permanecer al menos un administrador activo");
        jdbc.update("DELETE FROM user_permission_overrides WHERE user_id=?",id);
        jdbc.update("DELETE FROM users WHERE id=?",id);
        audit("USER_DELETE",id,user.username(),null);
    }
    public List<UserView> users() { return jdbc.queryForList("SELECT id FROM users ORDER BY username_normalized",String.class).stream().map(this::view).toList(); }
    public UserView view(String id) {
        var effectivePermissions=find(id).permissions();
        Map<String,String> overrides=new LinkedHashMap<>();
        jdbc.query("SELECT permission,effect FROM user_permission_overrides WHERE user_id=?",(org.springframework.jdbc.core.RowCallbackHandler) row->overrides.put(row.getString(1),row.getString(2)),id);
        return jdbc.queryForObject("SELECT * FROM users WHERE id=?",(r,n)-> {
            return new UserView(id,r.getString("username"),r.getString("email"),r.getString("email_verified_at"),r.getString("role"),r.getString("status"),r.getBoolean("must_change_password"),r.getString("temporary_password_expires_at"),r.getString("created_at"),r.getString("approved_at"),r.getString("approved_by"),overrides,effectivePermissions);
        },id);
    }
    /** Participates in the caller's reset transaction. */
    public void clear() {
        jdbc.update("DELETE FROM user_permission_overrides"); jdbc.update("DELETE FROM users");
        jdbc.update("UPDATE security_policy SET registration_enabled=0,approval_required=1,idle_minutes=30,maximum_hours=12,password_minimum_length=? WHERE id=1", defaultPasswordMinimumLength);
    }
    private String randomPassword() {
        byte[] bytes=new byte[Math.max(24, (policy().passwordMinimumLength() * 3 + 3) / 4)]; new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
