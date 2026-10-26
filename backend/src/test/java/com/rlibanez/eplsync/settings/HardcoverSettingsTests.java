package com.rlibanez.eplsync.settings;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

class HardcoverSettingsTests {
    static final String CIPHER="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    SingleConnectionDataSource data;
    JdbcTemplate jdbc;
    HardcoverSettings settings;
    @BeforeEach void setup() {
        data=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY,setting_value TEXT NOT NULL)");
        settings=new HardcoverSettings(jdbc,CIPHER,"Bearer env-test-key");
    }
    @AfterEach void close() {data.destroy();}
    static String token(String payload) {return "e30."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+".signature";}
    @Test void readsOnlyDeclaredExpiryWithoutInventingOrValidatingCredentials() {
        long now=java.time.Instant.now().getEpochSecond();
        var expired=HardcoverSettings.expiration(token("{\"exp\":"+(now-60)+"}"));assertThat(expired.expired()).isTrue();assertThat(expired.expiresSoon()).isFalse();
        var soon=HardcoverSettings.expiration(token("{\"exp\":"+(now+86400)+"}"));assertThat(soon.expired()).isFalse();assertThat(soon.expiresSoon()).isTrue();
        var future=HardcoverSettings.expiration(token("{\"exp\":"+(now+30*86400)+"}"));assertThat(future.expiresSoon()).isFalse();assertThat(future.expiresAt().getEpochSecond()).isEqualTo(now+30*86400);
        for(String key:java.util.List.of("opaque-test-key","broken.token.signature",token("{}"),token("{\"exp\":\"tomorrow\"}"),token("{\"exp\":999999999999999999999}"))) assertThat(HardcoverSettings.expiration(key)).isNull();
    }
    @Test void readsExpiryFromTheEffectiveEncryptedOrEnvironmentKeyAndHidesItWhenCleared() {
        long deadline=java.time.Instant.now().plusSeconds(86400).getEpochSecond();String token=token("{\"exp\":"+deadline+",\"private\":\"secret-claim-only\"}");
        var environment=new HardcoverSettings(jdbc,CIPHER,token);assertThat(environment.expiration().expiresAt().getEpochSecond()).isEqualTo(deadline);
        settings.save(new HardcoverSettings.Input(token,false));assertThat(settings.expiration().expiresSoon()).isTrue();
        settings.save(new HardcoverSettings.Input("",true));assertThat(settings.expiration()).isNull();
    }
    @Test void environmentIsNotCopiedToTheDatabaseAndViewsNeverContainSecrets() {
        assertThat(settings.view()).isEqualTo(new HardcoverSettings.View(true,true,false));
        assertThat(settings.apiKey()).isEqualTo("env-test-key");
        settings.save(new HardcoverSettings.Input("",false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings",Integer.class)).isZero();
        assertThat(settings.view().toString()).doesNotContain("env-test-key");
        assertThat(new HardcoverSettings.Input("private-test-key",false).toString()).doesNotContain("private-test-key");
    }
    @Test void keysAreEncryptedAndCanBeKeptReplacedClearedAndRestored() {
        settings.save(new HardcoverSettings.Input("Bearer saved-test-key",false));
        String encrypted=jdbc.queryForObject("SELECT setting_value FROM app_settings",String.class);
        assertThat(encrypted).startsWith(CredentialCipher.PREFIX).doesNotContain("saved-test-key");
        assertThat(settings.apiKey()).isEqualTo("saved-test-key");
        settings.save(new HardcoverSettings.Input("",false));assertThat(settings.apiKey()).isEqualTo("saved-test-key");
        settings.save(new HardcoverSettings.Input("replacement-test-key",false));assertThat(settings.apiKey()).isEqualTo("replacement-test-key");
        settings.save(new HardcoverSettings.Input("",true));assertThat(settings.apiKey()).isEmpty();
        assertThat(new HardcoverSettings(jdbc,CIPHER,"env-test-key").apiKey()).isEmpty();
        assertThat(settings.view()).isEqualTo(new HardcoverSettings.View(false,true,true));
        settings.restore();assertThat(settings.apiKey()).isEqualTo("env-test-key");
    }
    @Test void missingOrWrongEncryptionKeyNeverOverwritesCredentialsOrFallsBackToEnvironment() {
        var missing=new HardcoverSettings(jdbc,"","env-test-key");
        assertThatThrownBy(()->missing.save(new HardcoverSettings.Input("secret-test-key",false))).isInstanceOf(SecretKeyRequiredException.class);
        settings.save(new HardcoverSettings.Input("secret-test-key",false));
        var wrong=new HardcoverSettings(jdbc,"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","env-test-key");
        assertThatThrownBy(wrong::apiKey).hasMessageContaining("EPLSYNC_SECRET_KEY").hasMessageNotContaining("secret-test-key");
        assertThat(settings.apiKey()).isEqualTo("secret-test-key");
        wrong.save(new HardcoverSettings.Input("",true));assertThat(wrong.apiKey()).isEmpty();
    }
    @Test void invalidInputNeverWritesTheSecret() {
        for(String value:java.util.List.of("x".repeat(8193),"bad\nkey","bad key"))
            assertThatThrownBy(()->settings.save(new HardcoverSettings.Input(value,false))).hasMessageContaining("formato");
        assertThatThrownBy(()->settings.save(new HardcoverSettings.Input("secret-test-key",true))).hasMessageContaining("a la vez");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_settings",Integer.class)).isZero();
    }
    @Test void runningClientUsesNewCredentialsWithoutRestartAndConnectionCheckDoesNotSave() throws Exception {
        var headers=new java.util.ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{headers.add(e.getRequestHeaders().getFirst("Authorization"));byte[] body="{\"data\":{\"books\":[]}}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try {
            var client=new HardcoverClient(settings,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
            client.query("query{books{id}}",java.util.Map.of());
            client.checkKey("draft-test-key");assertThat(settings.apiKey()).isEqualTo("env-test-key");
            settings.save(new HardcoverSettings.Input("new-test-key",false));client.query("query{books{id}}",java.util.Map.of());
            assertThat(headers).containsExactly("Bearer env-test-key","Bearer draft-test-key","Bearer new-test-key");
            settings.save(new HardcoverSettings.Input("",true));assertThat(client.configured()).isFalse();
            assertThatThrownBy(()->client.query("query{books{id}}",java.util.Map.of())).hasMessageContaining("Ajustes");assertThat(headers).hasSize(3);
        } finally {server.stop(0);}
    }
}
