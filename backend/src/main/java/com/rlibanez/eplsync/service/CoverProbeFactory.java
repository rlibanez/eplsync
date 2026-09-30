package com.rlibanez.eplsync.service;

import com.rlibanez.eplsync.config.CoverCheckProperties;
import org.springframework.stereotype.Component;

@Component
public class CoverProbeFactory {
    public CoverProbe create(CoverCheckProperties options) { return new CoverProbe(options); }
}
