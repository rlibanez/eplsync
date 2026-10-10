package com.rlibanez.eplsync.updates;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class ApplicationVersion {
    public record Info(String version, String commit, String releasesUrl) {}
    private final Info info;
    public ApplicationVersion(ObjectProvider<BuildProperties> properties) {
        var build=properties.getIfAvailable();
        String version=build==null ? "unknown" : build.get("applicationVersion");
        if(version==null || version.isBlank()) version=build==null ? "unknown" : build.getVersion();
        String commit=build==null ? null : build.get("commit");
        if(commit==null || !commit.matches("[a-fA-F0-9]{7,40}")) commit=null;
        else commit=commit.substring(0,7).toLowerCase(java.util.Locale.ROOT);
        info=new Info(version,commit,"https://github.com/rlibanez/eplsync/releases");
    }
    public Info get() {return info;}
}
