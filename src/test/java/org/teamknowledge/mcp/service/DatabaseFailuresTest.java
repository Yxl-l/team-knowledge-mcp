package org.teamknowledge.mcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.dao.*;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseFailuresTest {
    @Test void sqlStatesSeparateRetryableFailuresFromPermanentErrors() {
        for(String[] pair:new String[][]{{"55P03","DB_BUSY"},{"40001","DB_BUSY"},{"57014","DB_TIMEOUT"},
                {"08006","DB_UNAVAILABLE"},{"23505","DB_CONSTRAINT"},{"42P01","SCHEMA_NOT_READY"},
                {"42703","SCHEMA_NOT_READY"},{"42601","DB_ERROR"}}) {
            var error=new UncategorizedSQLException("secret-parameter","secret-sql",new SQLException("secret-driver-message",pair[0]));
            var failure=DatabaseFailures.classify(error);
            assertEquals(pair[1],failure.code());
            assertEquals(java.util.Set.of("55P03","40001","57014","08006").contains(pair[0]),failure.retryable());
            assertFalse(failure.message().contains("secret"));
        }
        assertEquals("DB_TIMEOUT",DatabaseFailures.classify(new QueryTimeoutException("hidden")).code());
        assertEquals("DB_UNAVAILABLE",DatabaseFailures.classify(new DataAccessResourceFailureException("hidden")).code());
    }
}
