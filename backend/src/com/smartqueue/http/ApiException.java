package com.smartqueue.http;

/** An error that is reported to the client as JSON {"error": "..."} with an HTTP status. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException tooMany(String message) {
        return new ApiException(429, message);
    }
}
