package com.rlibanez.eplsync.api;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.HttpMethod;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Builds operation JSON for integration fixtures, never URL parameters. */
public final class OperationRequest implements org.springframework.test.web.servlet.RequestBuilder {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<String,Object> body = new LinkedHashMap<>();
    private final boolean torrent;
    private final MockHttpServletRequestBuilder delegate;
    public org.springframework.mock.web.MockHttpServletRequest buildRequest(jakarta.servlet.ServletContext context) { return delegate.buildRequest(context); }
    public OperationRequest contentType(String type) { delegate.contentType(type); return this; }
    private OperationRequest(String path, boolean dryRun) {
        delegate = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
        contentType("application/json"); torrent = path.startsWith("/api/torrent/");
        body.put("dryRun",dryRun); write();
    }
    public static OperationRequest operation(String path, boolean dryRun) { return new OperationRequest(path,dryRun); }
    private void write() { delegate.content(JSON.writeValueAsString(body)); }
    public OperationRequest field(String name, String... values) {
        Object value = values.length > 1 || name.equals("status") || name.equals("sort") ? List.of(values) : scalar(values[0]);
        if (torrent && !Set.of("selection","includeNotFound","multipleHashes","page","size","sort","all","includeDetails","previousVersions").contains(name)) {
            @SuppressWarnings("unchecked") var filters = (Map<String,Object>)body.computeIfAbsent("filters",key -> new LinkedHashMap<>());
            filters.put(name,value);
        } else body.put(name,value);
        write(); return this;
    }
    private Object scalar(String value) {
        if (value.equals("true") || value.equals("false")) return Boolean.valueOf(value);
        try { return Long.valueOf(value); } catch (NumberFormatException ignored) { return value; }
    }
    public OperationRequest content(String text) {
        @SuppressWarnings("unchecked") Map<String,Object> values = JSON.readValue(text,Map.class);
        body.putAll(values); write(); return this;
    }
}
