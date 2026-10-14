package com.rlibanez.eplsync.torrent.updates;

import java.util.*;
import com.rlibanez.eplsync.torrent.downloads.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TorrentRemovalSafetyTests {
    RemoteTorrent torrent(String hash,String path,String root,boolean noData) {
        return new RemoteTorrent(hash,DownloadStatus.QUEUED,null,Set.of(),path,"book",root,noData);
    }
    @Test void eighteenKnownOldFilesRemainExclusiveWithEighteenNewMagnetsWithoutMetadata() {
        var remote=new ArrayList<RemoteTorrent>();
        for(int i=0;i<18;i++) {
            remote.add(torrent("old"+i,"/downloads/Epublibre/old"+i+".epub","/downloads/Epublibre",false));
            remote.add(torrent("new"+i,"","/downloads/Epublibre",true));
        }
        var paths=TorrentRemovalSafety.paths(remote);
        for(int i=0;i<18;i++) {
            assertThat(TorrentRemovalSafety.assess(remote.get(2*i),paths)).isEqualTo(TorrentRemovalSafety.PathSafety.EXCLUSIVE);
            assertThat(TorrentRemovalSafety.assess(remote.get(2*i+1),paths)).isEqualTo(TorrentRemovalSafety.PathSafety.UNKNOWN);
        }
    }
    @Test void unknownFilesOnlyRestrictTheirOwnPotentialDirectory() {
        var old=torrent("old","/downloads/books/old.epub","/downloads/books",false);
        var unrelated=torrent("other","","/downloads/music",false);
        assertThat(TorrentRemovalSafety.exclusivePath(old,TorrentRemovalSafety.paths(List.of(old,unrelated)))).isTrue();
        var ambiguous=torrent("other","","/downloads/books",false);
        assertThat(TorrentRemovalSafety.assess(old,TorrentRemovalSafety.paths(List.of(old,ambiguous)))).isEqualTo(TorrentRemovalSafety.PathSafety.UNKNOWN);
    }
    @Test void sharedFilesAndParentChildDirectoriesStayProtected() {
        var old=torrent("old","/downloads/books/old.epub","/downloads/books",false);
        for(String path:List.of("/downloads/books/old.epub","/downloads/books","/downloads/books/old.epub/child")) {
            var other=torrent("other",path,"/downloads",false);
            assertThat(TorrentRemovalSafety.assess(old,TorrentRemovalSafety.paths(List.of(old,other)))).isEqualTo(TorrentRemovalSafety.PathSafety.SHARED);
        }
    }
    @Test void malformedOrUnscopedPathsStillPreventUnsafeDeletion() {
        var old=torrent("old","/downloads/books/old.epub","/downloads/books",false);
        for(var other:List.of(torrent("other","",null,false),torrent("other","/downloads/../books","/unrelated",false)))
            assertThat(TorrentRemovalSafety.assess(old,TorrentRemovalSafety.paths(List.of(old,other)))).isEqualTo(TorrentRemovalSafety.PathSafety.UNKNOWN);
    }
}
