package com.rlibanez.eplsync.ordering;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Locale;

/** SQLite's built-in lower() handles ASCII only. Use identical rules for stored text and search terms. */
public final class UnicodeText {
    private UnicodeText() {}
    public static String lower(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
    public static jakarta.persistence.criteria.Expression<String> lower(jakarta.persistence.criteria.CriteriaBuilder cb,jakarta.persistence.criteria.Expression<String> value) {
        return cb.function("epl_lower",String.class,value);
    }
    public static void register(Connection connection) throws SQLException {
        org.sqlite.Function.create(connection,"epl_lower",new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                String value=value_text(0);
                if(value==null) result(); else result(lower(value));
            }
        });
    }
}
