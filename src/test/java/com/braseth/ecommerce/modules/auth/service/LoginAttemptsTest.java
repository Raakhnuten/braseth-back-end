package com.braseth.ecommerce.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.braseth.ecommerce.common.exception.ApiProblem;

class LoginAttemptsTest {

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final LoginAttempts attempts = new LoginAttempts(clock);

    private void fail(String email, int times) {
        for (int i = 0; i < times; i++) {
            attempts.recordFailure(email);
        }
    }

    @Test
    void allowsSignInBelowTheLimit() {
        fail("a@example.com", LoginAttempts.MAX_FAILURES - 1);
        assertThatCode(() -> attempts.checkAllowed("a@example.com")).doesNotThrowAnyException();
    }

    @Test
    void locksTheAddressAtTheLimit() {
        fail("a@example.com", LoginAttempts.MAX_FAILURES);
        assertThatThrownBy(() -> attempts.checkAllowed("a@example.com"))
                .isInstanceOfSatisfying(ApiProblem.class, problem -> {
                    assertThat(problem.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(problem.getCode()).isEqualTo("too_many_attempts");
                });
    }

    @Test
    void treatsAddressesCaseInsensitively() {
        fail("A@Example.com ", LoginAttempts.MAX_FAILURES);
        assertThatThrownBy(() -> attempts.checkAllowed("a@example.com")).isInstanceOf(ApiProblem.class);
    }

    @Test
    void leavesOtherAddressesAlone() {
        fail("a@example.com", LoginAttempts.MAX_FAILURES);
        assertThatCode(() -> attempts.checkAllowed("b@example.com")).doesNotThrowAnyException();
    }

    @Test
    void unlocksOnceTheWindowHasPassed() {
        fail("a@example.com", LoginAttempts.MAX_FAILURES);
        clock.advance(LoginAttempts.WINDOW);
        assertThatCode(() -> attempts.checkAllowed("a@example.com")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        fail("a@example.com", LoginAttempts.MAX_FAILURES - 1);
        attempts.recordSuccess("a@example.com");
        fail("a@example.com", LoginAttempts.MAX_FAILURES - 1);
        assertThatCode(() -> attempts.checkAllowed("a@example.com")).doesNotThrowAnyException();
    }
}
