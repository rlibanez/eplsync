package com.rlibanez.eplsync.importer;

import com.rlibanez.eplsync.dto.CatalogBookCsvRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class CatalogBookValidationTests {
    record Field(String name, int maximum, BiConsumer<CatalogBookCsvRow,String> setter) {}
    static Stream<Field> fields() {
        return Stream.of(new Field("Autor",16_384,(row, value) -> java.util.Objects.requireNonNull(row).setAuthor(value)),
            new Field("Título",4_096,(row, value) -> java.util.Objects.requireNonNull(row).setTitle(value)),
            new Field("Colección",4_096,(row, value) -> java.util.Objects.requireNonNull(row).setCollection(value)),
            new Field("Géneros",4_096,(row, value) -> java.util.Objects.requireNonNull(row).setGenres(value)),
            new Field("Sinopsis",524_288,(row, value) -> java.util.Objects.requireNonNull(row).setSynopsis(value)),
            new Field("Enlace(s)",65_536,(row, value) -> java.util.Objects.requireNonNull(row).setLinks(value)),
            new Field("Portada",8_192,(row, value) -> java.util.Objects.requireNonNull(row).setCoverUrl(value)));
    }
    private CatalogBookCsvRow valid() {
        var row=new CatalogBookCsvRow();row.setEplId(1L);row.setRevision(1.1);
        row.setAuthor("Author");row.setTitle("Title");return row;
    }
    @ParameterizedTest @MethodSource("fields")
    void acceptsBoundaryAndRejectsFirstExcessCharacter(Field field) {
        var row=valid();field.setter().accept(row,"a".repeat(field.maximum()));
        assertThatCode(() -> CatalogBookValidation.validate(row)).doesNotThrowAnyException();
        field.setter().accept(row,"a".repeat(field.maximum()+1));
        assertThatThrownBy(() -> CatalogBookValidation.validate(row)).hasMessageContaining(field.name())
            .hasMessageContaining(Integer.toString(field.maximum()));
    }
    @Test void validatesIdentityAndFinitePositiveRevision() {
        var row=valid();
        for (Long id:new Long[]{null,0L,-1L}) {
            row.setEplId(id);assertThatThrownBy(() -> CatalogBookValidation.validate(row)).hasMessageContaining("EPL Id");
        }
        row.setEplId(1L);
        for (Double revision:new Double[]{null,0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            row.setRevision(revision);assertThatThrownBy(() -> CatalogBookValidation.validate(row)).hasMessageContaining("Revisión");
        }
    }
    @Test void rejectsBlankRequiredFieldsAndCountsUnicodeCharacters() {
        for (String value:new String[]{null,""," \t ","\u00a0\u2003"}) {
            var row=valid();row.setAuthor(value);
            assertThatThrownBy(() -> CatalogBookValidation.validate(row)).hasMessageContaining("Autor");
            var titleRow=valid();titleRow.setTitle(value);
            assertThatThrownBy(() -> CatalogBookValidation.validate(titleRow)).hasMessageContaining("Título");
        }
        var row=valid();row.setAuthor("😀".repeat(16_384));
        assertThatCode(() -> CatalogBookValidation.validate(row)).doesNotThrowAnyException();
    }
}
