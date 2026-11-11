package com.braseth.ecommerce.modules.submission.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the public site sends, shaped like the web app's models
 * (quote.model.ts, contact.service.ts, newsletter.service.ts).
 */
public final class SubmissionBodies {

    private SubmissionBodies() {
    }

    static final String PRINT_METHODS = "silkscreen|embroidery|dtf|heat-transfer|sublimation|pad-printing";

    public record QuoteRequestBody(
            @Valid @NotNull(message = "Tell us how to reach you.") Contact contact,
            @Valid @NotNull Product product,
            @Valid @NotNull Design design,
            @Valid @Size(max = 50, message = "Send at most 50 products in one request.") List<Line> items,
            @Pattern(regexp = "free-visual") String purpose) {
    }

    public record Contact(
            @NotBlank(message = "Enter your name.") @Size(max = 120, message = "Keep your name under 120 characters.") String fullName,
            @Size(max = 200) String companyName,
            @Email(message = "Enter a valid email.") @Size(max = 255) String email,
            @NotBlank(message = "Enter a phone number.") @Size(max = 40) String phone,
            @Size(max = 80) String telegram,
            @NotNull(message = "Choose how we should reply.") @Pattern(regexp = "email|phone|telegram",
                    message = "Choose how we should reply.") String preferredContactMethod) {
    }

    public record Product(
            String productId,
            @Size(max = 4000, message = "Keep the description under 4,000 characters.") String productNote,
            @Min(value = 0, message = "Enter a quantity.") @Max(value = 1_000_000, message = "Enter a smaller quantity.") int quantity) {
    }

    public record Design(@NotNull @Size(max = 20) List<@NotBlank String> designFileIds) {
    }

    public record Line(
            @NotBlank String productId,
            @Min(value = 1, message = "Enter a quantity.") @Max(value = 1_000_000, message = "Enter a smaller quantity.") int quantity,
            @Pattern(regexp = PRINT_METHODS) String printMethod) {
    }

    public record QuoteRequestReceipt(String quoteId, String reference, Instant submittedAt, String status) {
    }

    public record ContactBody(
            @NotBlank(message = "Enter your name.") @Size(max = 120) String name,
            @NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") @Size(max = 255) String email,
            @Size(max = 40) String phone,
            @NotBlank(message = "Enter a subject.") @Size(max = 200) String subject,
            @NotBlank(message = "Enter a message.") @Size(max = 5000, message = "Keep the message under 5,000 characters.") String message) {
    }

    public record ContactReceipt(String reference) {
    }

    public record NewsletterBody(
            @NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") @Size(max = 255) String email) {
    }

    public record NewsletterReceipt(boolean subscribed) {
    }
}
