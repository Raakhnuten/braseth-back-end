package com.braseth.ecommerce.modules.auth.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of /api/v1/auth, shaped like the web app's models
 * (src/app/core/models/customer.model.ts) field for field.
 *
 * {@code recaptchaToken} on registration is the Google reCAPTCHA
 * token; it is required only while the check is on (see RecaptchaVerifier).
 */
public final class AuthBodies {

	private AuthBodies() {
	}

	public record LoginBody(
			@NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") String email,
			@NotBlank(message = "Enter your password.") String password, boolean rememberMe) {
	}

	public record RegisterBody(
			@NotBlank(message = "Enter your name.") @Size(max = 120, message = "Keep your name under 120 characters.") String fullName,
			@NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") @Size(max = 255) String email,
			@NotBlank(message = "Enter your phone number.") @Size(max = 40) String phone,
			@NotBlank(message = "Choose a password.") @Size(min = 8, max = 100, message = "Use at least 8 characters.") String password,
			@NotNull(message = "Choose an account type.") @Pattern(regexp = "individual|corporate", message = "Choose an account type.") String kind,
			@Size(max = 200) String companyName,
			String recaptchaToken) {
	}

	public record RefreshBody(@NotBlank String refreshToken) {
	}

	/** Logout works without a body too: the access token simply expires. */
	public record LogoutBody(String refreshToken) {
	}

	public record EmailBody(
			@NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") String email) {
	}

	public record TokenBody(@NotBlank String token) {
	}

	public record ResetBody(@NotBlank String token,
			@NotBlank(message = "Choose a password.") @Size(min = 8, max = 100, message = "Use at least 8 characters.") String password) {
	}

	public record AuthSession(String accessToken, String refreshToken, Instant expiresAt, CustomerView customer) {
	}

	/**
	 * What a successful registration returns. The account cannot sign in until its
	 * e-mail address is confirmed, so no session is issued here.
	 */
	public record RegistrationReceipt(String email, boolean verificationRequired) {
	}

	public record CustomerView(String id, String fullName, String email, String phone, String telegram,
			String preferredContactMethod, String kind, AddressView address, boolean emailVerified, Instant createdAt) {
	}

	public record AddressView(String line1, String line2, String city, String province, String postalCode,
			String country) {
	}
}
