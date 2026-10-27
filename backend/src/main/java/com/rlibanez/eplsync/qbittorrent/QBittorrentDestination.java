package com.rlibanez.eplsync.qbittorrent;

import java.net.URI;
import java.util.Locale;

/** Canonical identity of a qBittorrent endpoint, including its reverse-proxy base path. */
public final class QBittorrentDestination {
    private QBittorrentDestination() {}
    record Endpoint(String scheme,String host,int port,String path) {
        String canonical() {return scheme+"://"+host+(port==-1?"":":"+port)+path;}
    }
    public static String normalize(String value) {return parse(value).canonical();}
    static Endpoint parse(String value) {
        try {
            URI uri=URI.create(value).normalize();
            String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            String authority=uri.getRawAuthority();
            if(!(scheme.equals("http") || scheme.equals("https")) || authority==null || authority.contains("@")
                || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null) throw new IllegalArgumentException();
            String host=uri.getHost();int port=uri.getPort();
            if(host==null) {
                // Java treats Docker registry names as a registry authority, leaving both host and port unset.
                int colon=authority.lastIndexOf(':');
                host=colon<0?authority:authority.substring(0,colon);
                String label="[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?";
                if(!host.contains("_") || host.length()>253 || !host.matches(label+"(?:\\."+label+")*\\.?")) throw new IllegalArgumentException();
                port=colon<0?-1:port(authority.substring(colon+1));
            } else {
                String suffix=authority.substring(host.length());
                if(!suffix.isEmpty()) {
                    if(!suffix.startsWith(":")) throw new IllegalArgumentException();
                    port=port(suffix.substring(1));
                }
            }
            if(port==0 || port>65535 || port< -1) throw new IllegalArgumentException();
            if(scheme.equals("http") && port==80 || scheme.equals("https") && port==443) port=-1;
            String path=uri.getRawPath()==null?"":uri.getRawPath().replaceAll("/+$","");
            return new Endpoint(scheme,host.toLowerCase(Locale.ROOT),port,path);
        } catch(RuntimeException ex) {
            // Never include a rejected URL: it may contain a password in user-info.
            throw new com.rlibanez.eplsync.exception.UserInputException("El destino de qBittorrent debe ser una URL HTTP(S) con host y puerto válidos, sin credenciales, query ni fragmento");
        }
    }
    private static int port(String value) {
        if(!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
        int port=Integer.parseInt(value);if(port<1 || port>65535) throw new IllegalArgumentException();return port;
    }
}
