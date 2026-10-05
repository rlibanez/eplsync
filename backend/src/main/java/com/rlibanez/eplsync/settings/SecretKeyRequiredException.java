package com.rlibanez.eplsync.settings;

/** Missing key before the first secret is persisted; distinct from a lost/incorrect existing key. */
public final class SecretKeyRequiredException extends org.springframework.web.server.ResponseStatusException {
    public SecretKeyRequiredException() {
        super(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Configura EPLSYNC_SECRET_KEY y recrea el contenedor antes de guardar contraseñas o API keys. Genera una clave con: openssl rand -base64 32.");
    }
}
