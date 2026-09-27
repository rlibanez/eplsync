package com.rlibanez.eplsync.torrent.downloads;

import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface DownloadRepository extends JpaRepository<DownloadRecord, String>, JpaSpecificationExecutor<DownloadRecord> {
    Optional<DownloadRecord> findByClientInstanceIdAndEplIdAndHash(String instance, Long eplId, String hash);
    List<DownloadRecord> findByClientInstanceId(String instance);
    List<DownloadRecord> findByEplIdIn(Collection<Long> ids);
}
