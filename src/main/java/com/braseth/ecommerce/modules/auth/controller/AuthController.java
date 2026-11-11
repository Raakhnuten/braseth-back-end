package com.braseth.ecommerce.modules.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.braseth.ecommerce.modules.auth.dto.AuthBodies.AuthSession;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.CustomerView;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.EmailBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.LoginBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.LogoutBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.RefreshBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.RegisterBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.RegistrationReceipt;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.ResetBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.TokenBody;
import com.braseth.ecommerce.modules.auth.service.AuthService;
import com.braseth.ecommerce.security.jwt.CustomUserDetails;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Customer sign-in for the web app. Plain JSON bodies shaped like the web
 * app's models; failures are {@code {code, message, errors}} with a real
 * HTTP status.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    @PostMapping("/login")
    public Mono<AuthSession> login(@Valid @RequestBody LoginBody body) {
        return auth.login(body);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RegistrationReceipt> register(@Valid @RequestBody RegisterBody body) {
        return auth.register(body);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> resendVerification(@Valid @RequestBody EmailBody body) {
        return auth.resendVerification(body.email());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> verifyEmail(@Valid @RequestBody TokenBody body) {
        return auth.verifyEmail(body.token());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> forgotPassword(@Valid @RequestBody EmailBody body) {
        return auth.forgotPassword(body.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> resetPassword(@Valid @RequestBody ResetBody body) {
        return auth.resetPassword(body);
    }

    @PostMapping("/refresh")
    public Mono<AuthSession> refresh(@Valid @RequestBody RefreshBody body) {
        return auth.refresh(body.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(@RequestBody(required = false) LogoutBody body) {
        return auth.logout(body == null ? null : body.refreshToken());
    }

    @GetMapping("/me")
    public Mono<CustomerView> me(@AuthenticationPrincipal CustomUserDetails principal) {
        return auth.me(principal.getUserId());
    }
}
