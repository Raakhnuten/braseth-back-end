package com.braseth.ecommerce.common.exception;

import java.util.List;
import java.util.Map;

/**
 * The error body every /api/v1 failure carries, matching the web app's
 * error interceptor: {@code {code, message, errors}}. {@code errors} is
 * left out when there are no field-level messages.
 */
public record ProblemBody(String code, String message, Map<String, List<String>> errors) {

    public static ProblemBody of(ApiProblem problem) {
        return new ProblemBody(problem.getCode(), problem.getMessage(), problem.getErrors());
    }
}
