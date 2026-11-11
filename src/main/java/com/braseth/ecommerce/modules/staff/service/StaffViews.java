package com.braseth.ecommerce.modules.staff.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small pieces every staff view and query shares. */
public final class StaffViews {

    private StaffViews() {
    }

    /** A path id as a UUID; anything else is "not found", never a 500. */
    public static UUID uuid(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw notFound(what);
        }
    }

    public static ApiProblem notFound(String what) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not_found", "We could not find that " + what + ".");
    }

    public static int page(int page) {
        return Math.max(page, 1);
    }

    public static int size(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    public static void text(ObjectNode out, String field, String value) {
        if (value == null || value.isBlank()) {
            out.putNull(field);
        } else {
            out.put(field, value);
        }
    }

    public static void id(ObjectNode out, String field, UUID value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value.toString());
        }
    }

    public static void instant(ObjectNode out, String field, OffsetDateTime value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value.toInstant().toString());
        }
    }

    public static void date(ObjectNode out, String field, LocalDate value) {
        if (value == null) {
            out.putNull(field);
        } else {
            out.put(field, value.toString());
        }
    }

    /** Binds a value, or a typed null when it is null or blank text. */
    public static <T> GenericExecuteSpec bind(GenericExecuteSpec spec, String name, T value, Class<T> type) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return spec.bindNull(name, type);
        }
        return spec.bind(name, value instanceof String text ? text.trim() : value);
    }
}
