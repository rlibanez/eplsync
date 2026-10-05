package com.rlibanez.eplsync.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.UncategorizedSQLException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {
    @Test void unexpectedDatabaseErrorsDoNotExposeSqlOrExceptionMessages() {
        var request=new MockHttpServletRequest("POST", "/api/security/users");
        var exception=new UncategorizedSQLException("insert user", "INSERT INTO users(password_hash) VALUES(?)",
            new SQLException("SQLITE_CONSTRAINT_UNIQUE: users.username_normalized"));
        var response=new GlobalExceptionHandler().handleGenericException(exception,request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().details()).isEqualTo("Ha ocurrido un error inesperado");
    }
}
