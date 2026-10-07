package com.rlibanez.eplsync.security;

/** Stable capabilities checked by operations, independently of role membership. */
public enum Permission {
    CATALOG_READ, BOOK_HISTORY_READ, DOWNLOADS_READ, DOWNLOADS_DELETE, TORRENT_SEND, TORRENT_SYNC,
    TORRENT_JOBS_MANAGE, TORRENT_CLEANUP, TORRENT_FILES_DELETE,
    CATALOG_IMPORT, CATALOG_DELETE, COVERS_MANAGE, EVENTS_MANAGE, SETTINGS_MANAGE;
    public static java.util.Set<Permission> defaults(String role) {
        return role.equals("ADMIN") ? java.util.EnumSet.allOf(Permission.class) : java.util.EnumSet.of(CATALOG_READ);
    }
    public static boolean has(Permission permission) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(permission.name()));
    }
    public static void require(Permission permission) {
        if (!has(permission)) throw new org.springframework.security.access.AccessDeniedException("Permiso requerido: " + permission);
    }
}
