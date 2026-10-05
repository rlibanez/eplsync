package com.rlibanez.eplsync.settings;

import com.rlibanez.eplsync.config.*;
import com.rlibanez.eplsync.events.EventSettings;
import com.rlibanez.eplsync.qbittorrent.QBittorrentProperties;
import com.rlibanez.eplsync.qbittorrent.QBittorrentDestination;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persisted overrides of installation defaults, published atomically after commit. */
@Service
@org.springframework.context.annotation.DependsOn("entityManagerFactory")
public class ServerSettings {
    public record Field(String key, String type, Object value, boolean overridden, boolean configured) {}
    public record View(String section, List<Field> fields) {}
    private record Definition(String section, String key, String type) {}
    public record Snapshot(TorrentProperties torrent, QBittorrentProperties qbittorrent,
            CatalogImportProperties catalogImport, CoverCheckProperties covers, EventSettings events, String zipUrl) {}
    private static final String DESTINATION = "torrent.credentials-destination";
    private static final String URL = "torrent.base-url";
    private static final String ENABLED = "torrent.enabled";
    private static final List<String> CREDENTIALS = List.of("torrent.qbittorrent.auth.username",
        "torrent.qbittorrent.auth.password", "torrent.qbittorrent.auth.api-key");
    private final Map<String, Object> installation = new LinkedHashMap<>();
    private final List<Definition> definitions = new ArrayList<>();
    private final Binder installationBinder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ThreadLocal<Snapshot> pinned = new ThreadLocal<>();
    private volatile Snapshot current;
    private Map<String, Object> overrides = Map.of();
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    public ServerSettings(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment env,
            TorrentProperties t, QBittorrentProperties q, CatalogImportProperties i, CoverCheckProperties c, EventSettings e) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.installationBinder = Binder.get(env);
        add("catalog", "catalog.zip-url", "text", env.getRequiredProperty("eplsync.catalog.zip-url"));
        add("catalog", "catalog.import.retention", "duration", i.getRetention());
        add("covers", "catalog.cover-check.connect-timeout", "duration", c.getConnectTimeout());
        add("covers", "catalog.cover-check.request-timeout", "duration", c.getRequestTimeout());
        add("covers", "catalog.cover-check.batch-timeout", "duration", c.getBatchTimeout());
        add("covers", "catalog.cover-check.concurrency", "number", c.getConcurrency());
        add("events", "events.retention.max-count", "number", e.getRetention().getMaxCount());
        add("events", "events.retention.max-age-days", "number", e.getRetention().getMaxAgeDays());
        add("torrent", "torrent.enabled", "boolean", t.isEnabled());
        add("torrent", "torrent.client", "client", t.getClient());
        add("torrent", "torrent.base-url", "text", t.getBaseUrl());
        add("torrent", "torrent.connect-timeout", "duration", t.getConnectTimeout());
        add("torrent", "torrent.request-timeout", "duration", t.getRequestTimeout());
        add("torrent", "torrent.trackers", "list", t.getTrackers());
        add("torrent", "torrent.bulk.multiple-hashes", "hashes", t.getBulk().getMultipleHashes().name().toLowerCase(Locale.ROOT));
        add("torrent", "torrent.bulk.batch-size", "number", t.getBulk().getBatchSize());
        add("torrent", "torrent.bulk.concurrency", "number", t.getBulk().getConcurrency());
        add("torrent", "torrent.bulk.interval", "duration", t.getBulk().getInterval());
        add("torrent", "torrent.download.start", "boolean", t.getDownload().isStart());
        add("torrent", "torrent.download.save-path", "text", t.getDownload().getSavePath());
        add("torrent", "torrent.rename.enabled", "boolean", t.getRename().isEnabled());
        add("torrent", "torrent.rename.pattern", "text", t.getRename().getPattern());
        add("torrent", "torrent.qbittorrent.download.category", "text", q.getDownload().getCategory());
        add("torrent", "torrent.qbittorrent.download.tags", "list", q.getDownload().getTags());
        add("torrent", "torrent.qbittorrent.download.auto-management", "boolean", q.getDownload().isAutoManagement());
        add("torrent", "torrent.qbittorrent.auth.mode", "auth", q.getAuth().getMode().name().toLowerCase(Locale.ROOT).replace('_','-'));
        add("torrent", "torrent.qbittorrent.auth.username", "text", q.getAuth().getUsername());
        add("torrent", "torrent.qbittorrent.auth.password", "secret", q.getAuth().getPassword());
        add("torrent", "torrent.qbittorrent.auth.api-key", "secret", q.getAuth().getApiKey());
        Map<String, Object> saved = new LinkedHashMap<>();
        jdbc.query("select setting_key, setting_value from app_settings", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            String key = row.getString(1);
            if (installation.containsKey(key) || key.equals(DESTINATION)) saved.put(key, JSON.readValue(row.getString(2), Object.class));
        });
        // Existing credentials with an unknown or changed destination must never be rebound silently.
        var loaded = new LinkedHashMap<>(saved);
        String destination = destination(saved);
        boolean hasSavedCredentials = CREDENTIALS.stream().anyMatch(key -> saved.containsKey(key) && !String.valueOf(saved.get(key)).isBlank());
        String binding = (String) saved.get(DESTINATION);
        if (binding == null && hasSavedCredentials && destination.equals(destination(Map.of()))) {
            saved.put(DESTINATION, destination);
        } else if (hasSavedCredentials && !destination.equals(binding)) {
            CREDENTIALS.forEach(key -> saved.put(key, ""));
            saved.put(ENABLED, false);
            saved.put(DESTINATION, destination);
        }
        if (!destination.equals(destination(Map.of())) && binding == null) saved.put(ENABLED, false);
        maskInstallationCredentials(saved);
        if (!saved.equals(loaded)) publish(saved);
        else { current = build(saved); overrides = Map.copyOf(saved); }
        t.useEffective(() -> snapshot().torrent()); q.useEffective(() -> snapshot().qbittorrent());
        i.useEffective(() -> snapshot().catalogImport()); c.useEffective(() -> snapshot().covers());
        e.useEffective(() -> snapshot().events());
    }
    private void add(String section, String key, String type, Object value) {
        definitions.add(new Definition(section, key, type));
        installation.put(key, value instanceof Duration d
            ? installationBinder.bind("eplsync." + key, String.class).orElse(d.toString())
            : value == null ? "" : value);
    }
    public Snapshot snapshot() { var value = pinned.get(); return value == null ? current : value; }
    public AutoCloseable pin() { return pin(snapshot()); }
    public AutoCloseable pin(Snapshot value) {
        var previous = pinned.get(); pinned.set(value);
        return () -> { if (previous == null) pinned.remove(); else pinned.set(previous); };
    }
    private List<Definition> section(String section) {
        var fields = definitions.stream().filter(f -> f.section().equals(section)).toList();
        if (fields.isEmpty()) throw new IllegalArgumentException("Apartado de ajustes desconocido");
        return fields;
    }
    public synchronized View view(String section) {
        return new View(section, section(section).stream().map(f -> {
            Object value = overrides.getOrDefault(f.key(), installation.get(f.key()));
            boolean secret = f.type().equals("secret");
            return new Field(f.key(), f.type(), secret ? "" : value, overrides.containsKey(f.key()), secret && !String.valueOf(value).isBlank());
        }).toList());
    }
    public synchronized View save(String section, Map<String, Object> values) {
        var fields = section(section);
        Map<String, Object> next = new LinkedHashMap<>(overrides);
        for (var entry : values.entrySet()) {
            var field = fields.stream().filter(f -> f.key().equals(entry.getKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Ajuste desconocido para este apartado"));
            Object value = entry.getValue();
            if (value == null) { next.remove(field.key()); continue; }
            boolean valid = switch (field.type()) {
                case "boolean" -> value instanceof Boolean;
                case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.intValue();
                case "list" -> value instanceof List<?> list && list.size() <= 100 && list.stream().allMatch(v -> v instanceof String s && s.length() <= 2048);
                default -> value instanceof String s && s.length() <= 4096;
            };
            if (!valid) throw new IllegalArgumentException("Tipo o tamaño de ajuste inválido: " + field.key());
            if (value instanceof List<?> list) value = list.stream().map(v -> ((String) v).trim()).filter(v -> !v.isEmpty()).toList();
            next.put(field.key(), value);
        }
        if (section.equals("torrent")) {
            String destination = destination(next);
            boolean changed = !destination.equals(QBittorrentDestination.normalize(current.torrent().getBaseUrl()));
            if (changed) {
                // Only credentials explicitly supplied in this request belong to the new endpoint.
                CREDENTIALS.forEach(key -> next.put(key, values.get(key) instanceof String text ? text : ""));
                next.put(ENABLED, false);
            }
            if (changed || CREDENTIALS.stream().anyMatch(values::containsKey)) next.put(DESTINATION, destination);
            maskInstallationCredentials(next);
            if (changed && Boolean.TRUE.equals(values.get(ENABLED))) {
                var candidate = build(next);
                try { candidate.qbittorrent().validate(); next.put(ENABLED, true); }
                catch (IllegalArgumentException ignored) { /* Keep disabled until complete credentials are configured. */ }
            }
        }
        publish(next);
        return view(section);
    }
    public synchronized View restore(String section) {
        Map<String, Object> next = new LinkedHashMap<>(overrides);
        section(section).forEach(f -> next.remove(f.key()));
        if (section.equals("torrent")) next.remove(DESTINATION);
        publish(next); return view(section);
    }
    private String destination(Map<String, Object> values) {
        return QBittorrentDestination.normalize((String) values.getOrDefault(URL, installation.get(URL)));
    }
    private void maskInstallationCredentials(Map<String, Object> values) {
        if (!destination(values).equals(destination(Map.of()))) {
            // An explicit empty override prevents fallback to credentials belonging to the installation URL.
            CREDENTIALS.forEach(key -> { if (!values.containsKey(key)) values.put(key, ""); });
        }
    }
    private void publish(Map<String, Object> next) {
        Snapshot candidate = build(next);
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from app_settings");
            next.forEach((key, value) -> jdbc.update("insert into app_settings(setting_key,setting_value) values (?,?)", key, JSON.writeValueAsString(value)));
        });
        overrides = Map.copyOf(next); current = candidate;
    }
    /** Called only after the full database reset transaction commits. */
    public synchronized void resetAfterCommit() { overrides = Map.of(); current = build(Map.of()); }
    private Snapshot build(Map<String, Object> values) {
        Map<String, Object> merged = new LinkedHashMap<>(installation); merged.putAll(values);
        String target = destination(values);
        if (CREDENTIALS.stream().anyMatch(key -> values.containsKey(key) && !String.valueOf(values.get(key)).isBlank())
                && !target.equals(values.get(DESTINATION))) {
            throw new IllegalArgumentException("Las credenciales de qBittorrent no corresponden al destino configurado");
        }
        if (!target.equals(destination(Map.of()))) {
            CREDENTIALS.forEach(key -> { if (!values.containsKey(key)) merged.put(key, ""); });
        }
        Map<String, Object> source = new LinkedHashMap<>();
        merged.put(URL, target);
        merged.forEach((key, value) -> { if (!key.equals(DESTINATION)) source.put("eplsync." + key, value); });
        try {
            var binder = new Binder(new MapConfigurationPropertySource(source));
            var t = binder.bind("eplsync.torrent", Bindable.of(TorrentProperties.class)).get();
            var q = binder.bind("eplsync.torrent.qbittorrent", Bindable.of(QBittorrentProperties.class)).get();
            var i = binder.bind("eplsync.catalog.import", Bindable.of(CatalogImportProperties.class)).get();
            var c = binder.bind("eplsync.catalog.cover-check", Bindable.of(CoverCheckProperties.class)).get();
            var e = binder.bind("eplsync.events", Bindable.of(EventSettings.class)).get();
            String url = (String) merged.get("catalog.zip-url");
            com.rlibanez.eplsync.importer.FileDownloader.validateUrl(url);
            // Validate even while disabled, so enabling later cannot publish invalid options.
            boolean enabled = t.isEnabled(); t.setEnabled(true); t.validate(); t.setEnabled(enabled);
            if (!t.getClient().equals(installation.get("torrent.client"))) throw new IllegalArgumentException();
            if (enabled && t.getClient().equals("qbittorrent")) q.validate();
            if (q.getDownload().getCategory().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            new com.rlibanez.eplsync.torrent.TorrentNameResolver().resolveTags(q.getDownload().getTags(), new com.rlibanez.eplsync.model.CatalogBook());
            if (!i.isRetentionValid() || !c.isTimeoutConfigurationValid() || c.getConcurrency() < 1 || c.getConcurrency() > 32) throw new IllegalArgumentException();
            var b = t.getBulk();
            if (b.getBatchSize() < 1 || b.getBatchSize() > 1000 || b.getConcurrency() < 1 || b.getConcurrency() > 16 || b.getMultipleHashes() == null
                || b.getInterval().isNegative() || b.getInterval().compareTo(Duration.ofSeconds(60)) > 0) throw new IllegalArgumentException();
            if (t.getConnectTimeout().compareTo(Duration.ofMinutes(5)) > 0 || t.getRequestTimeout().compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException();
            if (e.getRetention().getMaxCount() < 1 || e.getRetention().getMaxAgeDays() < 1) throw new IllegalArgumentException();
            com.rlibanez.eplsync.torrent.TorrentNameResolver.validatePattern(t.getRename().getPattern());
            return new Snapshot(t,q,i,c,e,url);
        } catch (RuntimeException ex) {
            // Binding exceptions may contain the rejected credential. Never expose their cause or value.
            throw new IllegalArgumentException("Configuración inválida. Revisa las URL, credenciales, patrones, límites y tiempos de espera (portadas: conexión <= petición <= lote). ");
        }
    }
}
