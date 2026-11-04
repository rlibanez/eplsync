package com.rlibanez.eplsync.settings;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: a disposable calibre-server library containing seed ID 1 in all four formats. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="EPLSYNC_TEST_CALIBRE_URL",matches=".+")
class CalibreFormatsNativeTests {
    @TempDir Path directory;
    @Test void nativeServerPreservesAllFormatsAndNeverReplacesAnExistingCopy() throws Exception {
        var client=new CalibreClient();
        var base=new CalibreSettings.Connection(System.getenv("EPLSYNC_TEST_CALIBRE_URL"),"","","library");
        var check=client.check(base);var c=new CalibreSettings.Connection(base.url(),"","",check.libraries().getFirst().id());
        var created=new ArrayList<Long>();
        try {
            Long target=null;
            for(String format:List.of("epub","pdf","mobi","azw3")) {
                Path file=directory.resolve("book."+format);client.downloadOriginalFormat(c,1,file,268435456,format);
                assertThat(Files.size(file)).isPositive();
                if(target==null) {target=client.uploadEpub(c,file,"book.epub","native-epub");created.add(target);}
                else {
                    var book=client.book(c,target);assertThat(client.addFormat(c,target,file,"book."+format,book.uuid())).isTrue();
                    assertThat(client.addFormat(c,target,file,"book."+format,book.uuid())).isFalse();
                    long independent=client.uploadBook(c,file,"book."+format,"native-"+format);created.add(independent);
                    Path independentCopy=directory.resolve("independent."+format);client.downloadOriginalFormat(c,independent,independentCopy,268435456,format);assertThat(Files.readAllBytes(independentCopy)).isEqualTo(Files.readAllBytes(file));
                }
                Path copy=directory.resolve("original."+format);client.downloadOriginalFormat(c,target,copy,268435456,format);
                assertThat(Files.readAllBytes(copy)).isEqualTo(Files.readAllBytes(file));
            }
            assertThat(client.formats(c,target)).contains("EPUB","PDF","MOBI","AZW3");
            Path changed=directory.resolve("changed.pdf");Files.writeString(changed,"%PDF-1.7 changed");
            assertThat(client.addFormat(c,target,changed,"changed.pdf",client.book(c,target).uuid())).isFalse();
            Path untouched=directory.resolve("untouched.pdf");client.downloadOriginalFormat(c,target,untouched,268435456,"pdf");assertThat(Files.readAllBytes(untouched)).isEqualTo(Files.readAllBytes(directory.resolve("book.pdf")));
        } finally {for(long id:created) client.remove(c,id,true);}
    }
}
