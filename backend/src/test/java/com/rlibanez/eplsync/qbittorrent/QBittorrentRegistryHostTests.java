package com.rlibanez.eplsync.qbittorrent;

/** Run the same authentication, streaming, timeout, write and redirect contract against a Docker registry host. */
class QBittorrentRegistryHostTests extends QBittorrentClientTests {
    @Override protected boolean dockerHost() {return true;}
}
