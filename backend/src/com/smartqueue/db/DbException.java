package com.smartqueue.db;

/** Unchecked wrapper for SQLException so service code stays readable. */
public class DbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
