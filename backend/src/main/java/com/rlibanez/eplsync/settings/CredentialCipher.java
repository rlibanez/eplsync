package com.rlibanez.eplsync.settings;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Database ciphertext is bound to its setting name; the AES key never enters SQLite. */
final class CredentialCipher {
    static final String PREFIX = "encrypted:v1:";
    private final byte[] key;
    CredentialCipher(String encodedKey) {
        try {
            if(encodedKey==null || !encodedKey.matches("[A-Za-z0-9+/]{43}=")) throw new IllegalArgumentException();
            byte[] decoded=Base64.getDecoder().decode(encodedKey);
            if(decoded.length!=32 || !Base64.getEncoder().encodeToString(decoded).equals(encodedKey)) throw new IllegalArgumentException();
            key=decoded;
        } catch(RuntimeException ex) { throw invalidKey(); }
    }
    static IllegalStateException invalidKey() {
        return new IllegalStateException("EPLSYNC_SECRET_KEY debe contener 32 bytes en Base64 estándar (44 caracteres, terminados en =). Genera una clave con: openssl rand -base64 32. Conserva la clave y no la cambies si ya hay credenciales guardadas.");
    }
    static IllegalStateException unavailable() {
        return new IllegalStateException("No se pueden recuperar las credenciales de qBittorrent. Comprueba que EPLSYNC_SECRET_KEY corresponde a esta base de datos y reinicia EPL Sync. La integración está bloqueada y los valores guardados se conservan.");
    }
    String encrypt(String name,String value) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] envelope=new byte[nonce.length+encrypted.length];
            System.arraycopy(nonce,0,envelope,0,nonce.length); System.arraycopy(encrypted,0,envelope,nonce.length,encrypted.length);
            return PREFIX+Base64.getEncoder().encodeToString(envelope);
        } catch(Exception ex) { throw unavailable(); }
    }
    String decrypt(String name,String value) {
        try {
            byte[] envelope=Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if(envelope.length<28) throw unavailable();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,envelope,0,12));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(envelope,12,envelope.length-12),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception ex) { throw unavailable(); }
    }
}
