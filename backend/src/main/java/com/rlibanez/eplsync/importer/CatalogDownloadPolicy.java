package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.exception.CatalogDownloadException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

/** All HTTP(S) origins are allowed; public downloads cannot redirect into non-public networks. */
final class CatalogDownloadPolicy {
    @FunctionalInterface interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
    record Target(URI uri, InetAddress[] addresses, boolean publicOnly) {
        Target { addresses = addresses.clone(); }
        @Override public InetAddress[] addresses() { return addresses.clone(); }
        String host() { return unbracket(uri.getHost()); }
    }
    private final Resolver resolver;
    CatalogDownloadPolicy(Resolver resolver) { this.resolver = resolver; }

    static URI validateUrl(String value) {
        try {
            URI uri = URI.create(value.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getHost().contains("%"))
                throw new com.rlibanez.eplsync.exception.UserInputException();
            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            return URI.create(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port == -1 ? "" : ":" + port) + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (RuntimeException ex) {
            // Parser exceptions and rejected URLs may contain credentials or query tokens.
            throw new com.rlibanez.eplsync.exception.UserInputException("La URL debe usar HTTP o HTTPS, con host y puerto válidos, sin credenciales ni fragmento");
        }
    }
    Target resolve(URI uri, boolean mustRemainPublic) throws CatalogDownloadException {
        InetAddress[] addresses;
        try { addresses = resolver.resolve(unbracket(uri.getHost())); }
        catch (UnknownHostException ex) { throw new CatalogDownloadException("No se pudo resolver el servidor del catálogo"); }
        if (addresses == null || addresses.length == 0 || Arrays.stream(addresses).anyMatch(java.util.Objects::isNull))
            throw new CatalogDownloadException("No se pudo resolver el servidor del catálogo");
        boolean publicOnly = Arrays.stream(addresses).allMatch(CatalogDownloadPolicy::isPublicAddress);
        if (mustRemainPublic && !publicOnly)
            throw new CatalogDownloadException("Una descarga iniciada en un destino público no puede redirigirse a una dirección interna o local");
        return new Target(uri, addresses, publicOnly);
    }
    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1,host.length()-1) : host;
    }
    static boolean isPublicAddress(InetAddress address) {
        return com.rlibanez.eplsync.network.PublicNetworkAddresses.isPublicAddress(address);
    }
}
