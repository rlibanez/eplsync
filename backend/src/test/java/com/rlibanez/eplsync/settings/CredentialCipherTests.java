package com.rlibanez.eplsync.settings;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CredentialCipherTests {
    private String randomKey() {
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    @Test void requiresCanonicalStandardBase64ForExactly32BytesWithoutExposingRejectedInput() {
        for(String key:new String[]{"","mi_password_secreto","not-a-key",java.util.Base64.getEncoder().encodeToString(new byte[60]),
            java.util.Base64.getEncoder().encodeToString(new byte[31]),randomKey().substring(0,43)," "+randomKey(),randomKey()+"\n",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB="}) {
            assertThatThrownBy(() -> new CredentialCipher(key)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand -base64 32").hasMessageNotContaining("mi_password_secreto");
        }
        assertThatThrownBy(() -> new CredentialCipher(null)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new CredentialCipher(randomKey())).doesNotThrowAnyException();
    }
    @Test void uniqueNoncesAndAuthenticationProtectValueAndSettingName() {
        String key=randomKey();var cipher=new CredentialCipher(key);
        String first=cipher.encrypt("password","very-private-value"),second=cipher.encrypt("password","very-private-value");
        assertThat(first).isNotEqualTo(second).doesNotContain("very-private-value");
        assertThat(new CredentialCipher(key).decrypt("password",first)).isEqualTo("very-private-value");
        assertThatThrownBy(() -> cipher.decrypt("api-key",first)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("password",first.substring(0,first.length()-5)+"AAAAA")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialCipher(randomKey()).decrypt("password",first)).isInstanceOf(IllegalStateException.class);
    }
}
