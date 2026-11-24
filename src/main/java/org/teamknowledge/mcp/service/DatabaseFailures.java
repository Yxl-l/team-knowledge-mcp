package org.teamknowledge.mcp.service;

import org.springframework.dao.*;
import java.sql.SQLException;

/** Public errors never include SQL text, parameters, connection URLs or driver messages. */
public final class DatabaseFailures {
    private DatabaseFailures() { }
    public record Failure(String code,String message,boolean retryable) { }
    public static String sqlState(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[0-9A-Z]{5}"))return sql.getSQLState();
        }
        return "UNKNOWN";
    }
    public static Failure classify(DataAccessException error) {
        String state=sqlState(error);
        if(state.equals("55P03") || state.startsWith("40") || error instanceof PessimisticLockingFailureException)
            return new Failure("DB_BUSY","The database is busy or a transaction conflicted. Retry with the same idempotency key.",true);
        if(state.equals("57014") || error instanceof QueryTimeoutException)
            return new Failure("DB_TIMEOUT","The operation timed out. Narrow the query scope and reuse the original idempotency key for write retries.",true);
        if(state.equals("42P01") || state.equals("42703"))
            return new Failure("SCHEMA_NOT_READY","The database schema does not match the server. Check the migrations.",false);
        if(state.startsWith("23") || error instanceof DataIntegrityViolationException)
            return new Failure("DB_CONSTRAINT","A database constraint rejected this write. Check the request and current revision.",false);
        if(state.startsWith("08") || error instanceof DataAccessResourceFailureException)
            return new Failure("DB_UNAVAILABLE","The database is unavailable. Check its connection status.",true);
        return new Failure("DB_ERROR","Database error. Use request_id to locate the server log entry.",false);
    }
}
