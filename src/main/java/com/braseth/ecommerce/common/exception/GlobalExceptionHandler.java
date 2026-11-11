package com.braseth.ecommerce.common.exception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns every error into a real HTTP status with a {@link ProblemBody}:
 * {code, message, errors?}. Messages are safe to show a customer; details of
 * unexpected failures go to the log only.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemBody> onProblem(ApiProblem problem) {
        return ResponseEntity.status(problem.getStatus()).body(ProblemBody.of(problem));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ProblemBody> onInvalid(WebExchangeBindException error) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        error.getFieldErrors().forEach(field -> errors
                .computeIfAbsent(field.getField(), key -> new ArrayList<>())
                .add(field.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ProblemBody("validation", "Please check the highlighted fields.", errors));
    }

    /** An upload over the multipart limit (see UploadLimitsConfig). */
    @ExceptionHandler(DataBufferLimitException.class)
    ResponseEntity<ProblemBody> onTooLarge(DataBufferLimitException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ProblemBody("file_too_large", "This file is too large.", null));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemBody> onUnreadable(ServerWebInputException error) {
        return ResponseEntity.badRequest()
                .body(new ProblemBody("bad_request", "The request could not be read.", null));
    }

    /** Framework errors that already carry their status: 404 no such path, 405, 406, 415, … */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemBody> onStatus(ResponseStatusException error) {
        HttpStatusCode status = error.getStatusCode();
        HttpStatus known = HttpStatus.resolve(status.value());
        String reason = known == null ? "Error" : known.getReasonPhrase();
        String code = reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return ResponseEntity.status(status).body(new ProblemBody(code, reason, null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemBody> onUnexpected(Exception error) {
        log.error("Unhandled error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ProblemBody("server_error", "Something went wrong on our side. Please try again.", null));
    }
}
