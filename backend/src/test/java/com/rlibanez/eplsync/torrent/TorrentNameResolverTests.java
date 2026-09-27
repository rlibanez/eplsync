package com.rlibanez.eplsync.torrent;

import com.rlibanez.eplsync.model.CatalogBook;
import com.rlibanez.eplsync.model.enums.Language;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class TorrentNameResolverTests {
    private final TorrentNameResolver resolver = new TorrentNameResolver();

    @Test
    void resolvesRequestedPatternIncludingRevisionAndPunctuation() {
        var book = CatalogBook.builder().eplId(2663L).author("Bronte, Charlotte")
                .title("Jane Eyre").revision(1.2).build();
        assertThat(resolver.resolve("{author} - {title} [{eplId}] (r{revision})", book))
                .isEqualTo("Bronte, Charlotte - Jane Eyre [2663] (r1.2)");
    }

    @Test
    void discoversAllModelFieldsButRejectsUnknownPropertiesAndExpressions() {
        for (var field : CatalogBook.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                assertThatCode(() -> TorrentNameResolver.validatePattern("{" + field.getName() + "}"))
                        .doesNotThrowAnyException();
            }
        }
        for (String pattern : new String[] {"{class}", "{unknown}", "{title.toString()}", "{language.name}", "{{title}}", "{title", "title}", "{}"}) {
            assertThatThrownBy(() -> TorrentNameResolver.validatePattern(pattern)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void formatsNullsDatesEnumsAndDecimalsDeterministically() {
        var book = CatalogBook.builder().revision(2.0).volume(1.5).publicationDate(LocalDate.of(2026, 9, 27))
                .language(Language.ESPANOL).build();
        assertThat(resolver.resolve("{revision}|{volume}|{publicationDate}|{language}|{collection}", book))
                .isEqualTo("2|1.5|2026-09-27|ESPANOL|");
        assertThatThrownBy(() -> resolver.resolve("{collection}", book)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesLiteralValuesWithoutRecursiveExpansionOrRegexSubstitution() {
        var book = CatalogBook.builder().title("$5 \\ {author} & + ñ").synopsis("uno\ndos").build();
        assertThat(resolver.resolve("[{title}] ({title})", book))
                .isEqualTo("[$5 \\ {author} & + ñ] ($5 \\ {author} & + ñ)");
        assertThat(resolver.resolve("{synopsis}", book)).isEqualTo("uno dos");
    }
}
