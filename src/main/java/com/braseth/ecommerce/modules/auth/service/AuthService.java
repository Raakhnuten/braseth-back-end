package com.braseth.ecommerce.modules.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.braseth.ecommerce.common.exception.ApiProblem;
import com.braseth.ecommerce.common.util.PhoneNumbers;
import com.braseth.ecommerce.infrastructure.recaptcha.RecaptchaVerifier;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.AddressView;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.AuthSession;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.CustomerView;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.LoginBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.RegisterBody;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.RegistrationReceipt;
import com.braseth.ecommerce.modules.auth.dto.AuthBodies.ResetBody;
import com.braseth.ecommerce.modules.auth.repository.AccountQueries;
import com.braseth.ecommerce.modules.user.entity.Permission;
import com.braseth.ecommerce.modules.user.entity.Role;
import com.braseth.ecommerce.modules.user.entity.User;
import com.braseth.ecommerce.security.jwt.JwtProperties;
import com.braseth.ecommerce.security.jwt.JwtService;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Customer sign-in: registration, login, token refresh, e-mail verification
 * and password reset. See {@link AccountQueries} for why the SQL is its own.
 */
@Slf4j
@Service
public class AuthService {

    private static final String INVALID_LOGIN = "That email and password do not match.";

    private static final String SESSION_ENDED = "Your session has ended. Please sign in again.";

    private final AccountQueries accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokens;
    private final AccountMail mail;
    private final RecaptchaVerifier recaptcha;
    private final LoginAttempts loginAttempts;
    private final TransactionalOperator transaction;

    AuthService(
            AccountQueries accounts,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties,
            TokenService tokenService,
            RefreshTokenService refreshTokens,
            AccountMail mail,
            RecaptchaVerifier recaptcha,
            LoginAttempts loginAttempts,
            ReactiveTransactionManager transactionManager) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.tokenService = tokenService;
        this.refreshTokens = refreshTokens;
        this.mail = mail;
        this.recaptcha = recaptcha;
        this.loginAttempts = loginAttempts;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /**
     * Repeated wrong passwords lock the address for a while (see
     * {@link LoginAttempts}). The password is checked before the account's
     * state, so a wrong password never reveals whether an address is
     * registered or unverified.
     */
    public Mono<AuthSession> login(LoginBody body) {
        String email = body.email().trim();
        return Mono.fromRunnable(() -> loginAttempts.checkAllowed(email))
                .then(accounts.findUserByEmail(email))
                .filterWhen(user -> passwordMatches(body.password(), user.getPasswordHash()))
                .switchIfEmpty(Mono.defer(() -> {
                    loginAttempts.recordFailure(email);
                    return Mono.error(ApiProblem.unauthorized("invalid_credentials", INVALID_LOGIN));
                }))
                .doOnNext(user -> loginAttempts.recordSuccess(email))
                .flatMap(user -> {
                    if (!user.isActive()) {
                        return Mono.error(ApiProblem.forbidden("account_disabled",
                                "This account is not active. Please contact BRASETH."));
                    }
                    if (!user.isEmailVerified()) {
                        return Mono.error(ApiProblem.forbidden("email_not_verified",
                                "Please confirm your email address first. Check your inbox for the link."));
                    }
                    return accounts.touchLastLogin(user.getId())
                            .then(refreshTokens.issue(user.getId()))
                            .flatMap(refreshToken -> session(user, refreshToken));
                });
    }

    public Mono<RegistrationReceipt> register(RegisterBody body) {
        return recaptcha.verify(body.recaptchaToken()).then(Mono.defer(() -> createAccount(body)));
    }

    private Mono<RegistrationReceipt> createAccount(RegisterBody body) {
        String email = body.email().trim();
        String phone = PhoneNumbers.normalise(body.phone());
        if (phone == null) {
            return Mono.error(ApiProblem.field("phone", "invalid_phone",
                    "Enter a phone number such as 012 345 678 or +855 12 345 678."));
        }
        boolean corporate = "corporate".equals(body.kind());
        String company = body.companyName() == null || body.companyName().isBlank() ? null : body.companyName().trim();
        if (corporate && company == null) {
            return Mono.error(ApiProblem.field("companyName", "company_required", "Enter your company name."));
        }

        return accounts.findUserByEmail(email).hasElement()
                .flatMap(taken -> taken
                        ? Mono.error(ApiProblem.conflict("email", "email_taken",
                                "An account with this email already exists. Try signing in instead."))
                        : accounts.phoneTaken(phone))
                .flatMap(taken -> taken
                        ? Mono.error(ApiProblem.conflict("phone", "phone_taken",
                                "An account with this phone number already exists."))
                        : hashPassword(body.password()).flatMap(hash -> accounts.insertCustomer(
                                body.fullName().trim(), email, phone, hash, corporate ? company : null)))
                .as(transaction::transactional)
                // Two sign-ups racing past the checks above meet the unique index here.
                .onErrorMap(DataIntegrityViolationException.class, error -> ApiProblem.conflict("email",
                        "email_taken", "An account with this email already exists. Try signing in instead."))
                .flatMap(accounts::findUserById)
                .flatMap(this::sendVerification)
                .thenReturn(new RegistrationReceipt(email, true));
    }

    /** Always succeeds, so it cannot be used to find out who has an account. */
    public Mono<Void> resendVerification(String email) {
        return accounts.findUserByEmail(email.trim())
                .filter(user -> user.isActive() && !user.isEmailVerified())
                .flatMap(this::sendVerification);
    }

    /** The link is used up in the same transaction that marks the address verified. */
    public Mono<Void> verifyEmail(String token) {
        return tokenService.consumeEmailVerification(token)
                .switchIfEmpty(Mono.error(() -> ApiProblem.badRequest("invalid_token",
                        "This link has expired or was already used. Ask for a new one.")))
                .flatMap(accounts::markEmailVerified)
                .as(transaction::transactional);
    }

    /** Always succeeds, so it cannot be used to find out who has an account. */
    public Mono<Void> forgotPassword(String email) {
        return accounts.findUserByEmail(email.trim())
                .filter(user -> user.isActive() && user.isEmailVerified())
                .flatMap(user -> tokenService.issuePasswordReset(user.getId())
                        .flatMap(token -> mail.passwordReset(user.getEmail(), user.getName(), token)));
    }

    /**
     * A reset link for an account, sent at a member of staff's request. It
     * goes to the address on the account; staff never see it. Answers false
     * when the account cannot sign in, so there is nothing to reset.
     */
    public Mono<Boolean> sendPasswordReset(UUID userId) {
        return accounts.findUserById(userId)
                .filter(user -> user.isActive() && user.isEmailVerified())
                .flatMap(user -> tokenService.issuePasswordReset(user.getId())
                        .flatMap(token -> mail.passwordReset(user.getEmail(), user.getName(), token))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    /**
     * The invitation for a new member of staff: a link to set their own
     * password, after which the account is active (see resetPassword).
     */
    public Mono<Void> sendStaffInvitation(UUID userId) {
        return accounts.findUserById(userId)
                .filter(user -> "invited".equals(user.getStatus()))
                .flatMap(user -> tokenService.issuePasswordReset(user.getId())
                        .flatMap(token -> mail.invitation(user.getEmail(), user.getName(), token)));
    }

    /** A new password also signs the account out everywhere. */
    public Mono<Void> resetPassword(ResetBody body) {
        return hashPassword(body.password())
                .flatMap(hash -> tokenService.consumePasswordReset(body.token())
                        .switchIfEmpty(Mono.error(() -> ApiProblem.badRequest("invalid_token",
                                "This reset link has expired or was already used. Ask for a new one.")))
                        .flatMap(userId -> accounts.setPasswordHash(userId, hash)
                                .then(accounts.activateInvited(userId))
                                .then(refreshTokens.revokeAll(userId)))
                        .as(transaction::transactional));
    }

    /** A new access token; the caller keeps the refresh token it already holds. */
    public Mono<AuthSession> refresh(String refreshToken) {
        return refreshTokens.userOf(refreshToken)
                .flatMap(accounts::findUserById)
                .filter(User::isEnabled)
                .switchIfEmpty(Mono.error(() -> ApiProblem.unauthorized("session_expired", SESSION_ENDED)))
                .flatMap(user -> session(user, refreshToken));
    }

    /** Ends the session for good. Without a refresh token there is nothing to end. */
    public Mono<Void> logout(String refreshToken) {
        return refreshToken == null || refreshToken.isBlank() ? Mono.empty() : refreshTokens.revoke(refreshToken);
    }

    public Mono<CustomerView> me(UUID userId) {
        return accounts.findUserById(userId)
                .switchIfEmpty(Mono.error(() -> ApiProblem.unauthorized("unauthenticated", "Please sign in to continue.")))
                .flatMap(this::customerView);
    }

    private Mono<Void> sendVerification(User user) {
        return tokenService.issueEmailVerification(user.getId())
                .flatMap(token -> mail.verification(user.getEmail(), user.getName(), token));
    }

    private Mono<AuthSession> session(User user, String refreshToken) {
        return Mono.zip(accounts.rolesOf(user.getId()), accounts.permissionsOf(user.getId()), customerView(user))
                .map(parts -> {
                    List<Role> roles = parts.getT1();
                    List<Permission> permissions = parts.getT2();
                    String access = jwtService.generateAccessToken(user, roles, permissions);
                    Instant expiresAt = Instant.now().plusMillis(jwtProperties.getAccessTokenExpiration());
                    return new AuthSession(access, refreshToken, expiresAt, parts.getT3());
                });
    }

    // BCrypt is deliberately slow (~100 ms), so it runs off the event loop.

    private Mono<Boolean> passwordMatches(String raw, String hash) {
        return Mono.fromCallable(() -> passwordEncoder.matches(raw, hash)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> hashPassword(String raw) {
        return Mono.fromCallable(() -> passwordEncoder.encode(raw)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<CustomerView> customerView(User user) {
        return accounts.customerRecord(user.getId())
                .map(profile -> view(user, profile))
                .defaultIfEmpty(view(user, null));
    }

    private static CustomerView view(User user, AccountQueries.CustomerRecord profile) {
        String company = profile == null ? null : profile.companyName();
        AddressView address = null;
        if (profile != null && profile.billingAddress() != null && !profile.billingAddress().isBlank()) {
            // One address column: a second line, when given, is stored on its own line.
            String[] lines = profile.billingAddress().split("\n", 2);
            address = new AddressView(lines[0], lines.length > 1 ? lines[1] : null, profile.billingCity(),
                    profile.billingProvince(), profile.billingPostalCode(), profile.billingCountry());
        }
        String contact = profile == null || profile.preferredContactMethod() == null ? "email"
                : profile.preferredContactMethod();
        return new CustomerView(
                user.getId().toString(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                profile == null ? null : profile.telegram(),
                contact,
                company == null || company.isBlank() ? "individual" : "corporate",
                address,
                user.isEmailVerified(),
                user.getCreatedAt());
    }
}
