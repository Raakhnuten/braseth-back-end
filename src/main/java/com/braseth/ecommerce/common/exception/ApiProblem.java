package com.braseth.ecommerce.common.exception;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * A failure the /api/v1 layer reports to the caller as-is: an HTTP status,
 * a stable machine-readable code, a message safe to show a customer, and
 * optional per-field messages keyed by the request's field names.
 */
@Getter
public class ApiProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, List<String>> errors;

    public ApiProblem(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiProblem(HttpStatus status, String code, String message, Map<String, List<String>> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public static ApiProblem badRequest(String code, String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiProblem field(String field, String code, String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, code, message, Map.of(field, List.of(message)));
    }

    public static ApiProblem unauthorized(String code, String message) {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiProblem forbidden(String code, String message) {
        return new ApiProblem(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiProblem conflict(String field, String code, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, code, message, Map.of(field, List.of(message)));
    }
}
