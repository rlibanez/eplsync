package com.rlibanez.eplsync.settings;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CalibreDownloadFilesTests {
    @TempDir Path directory;
    Path root() { return directory.resolve("downloads"); }

    @Test void startupRecoversEpubAndZipAfterAbruptProcessExit() throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),Crash.class.getName(),root().toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes())).isZero();
        try(var instances=Files.list(root())) {
            var instance=instances.findFirst().orElseThrow();
            try(var files=Files.list(instance)) { assertThat(files.count()).isEqualTo(3); }
        }
        try(var restarted=new CalibreDownloadFiles(root())) {
            restarted.cleanAbandoned();
            try(var instances=Files.list(root())) { assertThat(instances.count()).isZero(); }
            assertThat(Files.exists(restarted.create(false))).isTrue();
        }
    }

    public static class Crash {
        public static void main(String[] args) throws Exception {
            try(var files=new CalibreDownloadFiles(Path.of(args[0]))) {
                Files.writeString(files.create(false),"private epub");
                Files.writeString(files.create(true),"private zip");
                Runtime.getRuntime().halt(0);
            }
        }
    }

    @Test void cleanupNeverDeletesFilesOfAnotherLiveInstance() throws Exception {
        try(var running=new CalibreDownloadFiles(root());var other=new CalibreDownloadFiles(root())) {
            var epub=running.create(false);var zip=running.create(true);
            other.cleanAbandoned();
            assertThat(epub).exists();assertThat(zip).exists();
            other.close();
            assertThat(epub).exists();assertThat(zip).exists();
        }
        try(var instances=Files.list(root())) { assertThat(instances.count()).isZero(); }
    }

    @Test void cleanupLeavesUnknownFilesAndDirectoriesUntouched() throws Exception {
        Path instance;
        try(var running=new CalibreDownloadFiles(root())) {
            instance=running.create(false).getParent();
            Files.writeString(instance.resolve("unrelated.txt"),"keep");
            Files.createDirectory(instance.resolve("nested"));
        }
        try(var cleaner=new CalibreDownloadFiles(root())) { cleaner.cleanAbandoned(); }
        assertThat(instance.resolve("unrelated.txt")).hasContent("keep");
        assertThat(instance.resolve("nested")).isDirectory();
        try(var files=Files.list(instance)) { assertThat(files.map(p->p.getFileName().toString()).toList()).containsExactlyInAnyOrder(".lock","unrelated.txt","nested"); }
    }

    @Test void cleanupDoesNotFollowSymbolicLinks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(root().getFileSystem().supportedFileAttributeViews().contains("posix"));
        var outside=Files.createDirectory(directory.resolve("outside"));
        var book=Files.writeString(outside.resolve("epub-123.epub"),"keep");
        Path instance;
        try(var running=new CalibreDownloadFiles(root())) {
            instance=running.create(false).getParent();
            Files.createSymbolicLink(instance.resolve("epub-456.epub"),book);
            Files.createSymbolicLink(root().resolve("instance-11111111-1111-1111-1111-111111111111"),outside);
        }
        try(var cleaner=new CalibreDownloadFiles(root())) { cleaner.cleanAbandoned(); }
        assertThat(book).hasContent("keep");
        assertThat(Files.isSymbolicLink(instance.resolve("epub-456.epub"))).isTrue();
        var linkedRoot=directory.resolve("linked-root");Files.createSymbolicLink(linkedRoot,outside);
        try(var cleaner=new CalibreDownloadFiles(linkedRoot)) {
            cleaner.cleanAbandoned();
            assertThatThrownBy(()->cleaner.create(false)).isInstanceOf(java.io.IOException.class);
        }
        assertThat(book).hasContent("keep");
    }
}
