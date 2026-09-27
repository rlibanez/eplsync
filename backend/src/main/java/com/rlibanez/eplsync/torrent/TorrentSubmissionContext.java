package com.rlibanez.eplsync.torrent;

import java.util.Set;
import java.util.function.Supplier;

/** Estado efímero exclusivo de un envío individual o una ejecución bulk. */
public final class TorrentSubmissionContext {
    private Set<String> categories;

    public synchronized Set<String> categories(Supplier<Set<String>> loader) {
        if (categories == null) categories = Set.copyOf(loader.get());
        return categories;
    }
}
