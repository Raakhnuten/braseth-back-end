package com.braseth.ecommerce.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.braseth.ecommerce.common.exception.ApiProblem;

/**
 * Slows down password guessing: after {@link #MAX_FAILURES} wrong passwords
 * for one address inside {@link #WINDOW}, sign-in for that address is refused
 * with 429 until the window runs out. A successful sign-in clears the count.
 *
 * Counted per address rather than per IP, so a script cannot dodge it by
 * rotating addresses on one account. Kept in memory: enough for the single
 * API instance BRASETH runs; several instances would need a shared store.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private record Failures(int count, Instant windowStart) {
    }

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LoginAttempts() {
        this(Clock.systemUTC());
    }

    LoginAttempts(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 while the address is locked out. */
    public void checkAllowed(String email) {
        Failures current = failures.get(key(email));
        if (current == null || expired(current)) {
            return;
        }
        if (current.count() >= MAX_FAILURES) {
            Duration left = Duration.between(clock.instant(), current.windowStart().plus(WINDOW));
            long minutes = Math.max(1, (left.toSeconds() + 59) / 60);
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "Too many failed sign-in attempts. Please try again in " + minutes
                            + (minutes == 1 ? " minute." : " minutes."));
        }
    }

    public void recordFailure(String email) {
        failures.compute(key(email), (k, current) -> current == null || expired(current)
                ? new Failures(1, clock.instant())
                : new Failures(current.count() + 1, current.windowStart()));
    }

    public void recordSuccess(String email) {
        failures.remove(key(email));
    }

    /** Drops stale entries so the map cannot grow without bound. */
    @Scheduled(fixedDelay = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void forgetExpired() {
        failures.values().removeIf(this::expired);
    }

    private boolean expired(Failures entry) {
        return !clock.instant().isBefore(entry.windowStart().plus(WINDOW));
    }

    private static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
