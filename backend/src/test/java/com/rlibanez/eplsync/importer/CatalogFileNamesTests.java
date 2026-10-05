package com.rlibanez.eplsync.importer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogFileNamesTests {
    @Test void stripsPathsAndControlCharactersFromDisplayMetadata() {
        assertThat(CatalogFileNames.display("C:\\tmp\\ca\r\nta\u001blog\u202e.zip", "catalog.zip"))
            .isEqualTo("catalog.zip");
        assertThat(CatalogFileNames.display("https://unused/folder/books.zip", "catalog.zip")).isEqualTo("books.zip");
    }
    @Test void limitsLengthByCodePointsAndNormalizesUnicode() {
        assertThat(CatalogFileNames.display("x".repeat(500),"fallback")).hasSize(200);
        assertThat(CatalogFileNames.display("cafe\u0301.csv","fallback")).isEqualTo("café.csv");
        assertThat(CatalogFileNames.display("😀".repeat(201),"fallback").codePointCount(0,400)).isEqualTo(200);
    }
    @Test void replacesEmptyOrRelativeDisplayNames() {
        for (String name : new String[]{"", "..", ".", "folder/", "\n"})
            assertThat(CatalogFileNames.display(name,"catalog.zip")).isEqualTo("catalog.zip");
    }
    @Test void keepsShellAndHtmlCharactersAsInertDisplayText() {
        assertThat(CatalogFileNames.display("$(echo test);<img>.zip","fallback")).isEqualTo("$(echo test);<img>.zip");
    }
}
