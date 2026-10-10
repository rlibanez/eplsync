package com.rlibanez.eplsync.ordering;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.util.*;

class TextOrderingTests {
    static final List<String> INPUT=List.of("Zeta","¿Quién?","Órbita","Niño","Nube","Ñandú","Árbol","Éxodo","Índice","Único","¡Bravo!","\"Casa\"","Árbol 10","Árbol 2","Árbol 1");
    static final List<String> EXPECTED=List.of("Árbol","Árbol 1","Árbol 2","Árbol 10","¡Bravo!","\"Casa\"","Éxodo","Índice","Niño","Nube","Ñandú","Órbita","¿Quién?","Único","Zeta");
    @Test void followsSpanishAlphabetAndNaturalNumbersWithoutChangingValues() {
        assertThat(INPUT.stream().sorted(TextOrdering::compare).toList()).isEqualTo(EXPECTED);
        assertThat(TextOrdering.compare("ÁRBOL","arbol")).isZero();
        assertThat(TextOrdering.compare("Nube","Ñandú")).isNegative();
        assertThat(TextOrdering.compare("Ñandú","Órbita")).isNegative();
        assertThat(TextOrdering.compare("Libro 0002","Libro 2")).isZero();
        assertThat(TextOrdering.compare("Libro 999999999999999999999","Libro 1000000000000000000000")).isNegative();
    }
    @Test void sqliteOrdersBeforePaginationAndPreservesEquality() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            TextOrdering.register(connection);
            try(var sql=connection.createStatement()) {sql.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY,title TEXT)");sql.execute("CREATE INDEX titles ON sample(title COLLATE EPL_TEXT,id)");}
            try(var insert=connection.prepareStatement("INSERT INTO sample VALUES(?,?)")) {
                for(int i=0;i<INPUT.size();i++) {insert.setInt(1,i);insert.setString(2,INPUT.get(i));insert.executeUpdate();}
            }
            var actual=new ArrayList<String>();
            for(int offset=0;offset<INPUT.size();offset+=4) {
                try(var query=connection.createStatement();var result=query.executeQuery("SELECT title FROM sample ORDER BY title COLLATE EPL_TEXT,id LIMIT 4 OFFSET "+offset)) {
                    while(result.next()) actual.add(result.getString(1));
                }
            }
            assertThat(actual).isEqualTo(EXPECTED);
            try(var query=connection.createStatement();var result=query.executeQuery("EXPLAIN QUERY PLAN SELECT title FROM sample ORDER BY title COLLATE EPL_TEXT,id LIMIT 4")) {
                assertThat(result.next()).isTrue();assertThat(result.getString("detail")).contains("INDEX titles");
            }
            try(var query=connection.createStatement();var result=query.executeQuery("SELECT count(*) FROM sample WHERE title='arbol'")) {
                assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
            }
        }
    }
}
