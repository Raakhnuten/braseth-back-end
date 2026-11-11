package com.braseth.ecommerce.modules.staff.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What the staff screens send (admin.model.ts). */
public final class StaffBodies {

    private StaffBodies() {
    }

    private static final String MAX_AMOUNT = "100000000";

    /** QuotePricingDraft: plain numbers in; the backend does the arithmetic. */
    public record PricingDraft(
            @NotNull @Pattern(regexp = "USD|KHR", message = "Choose USD or KHR.") String currency,
            @NotNull @Size(min = 1, max = 50, message = "Add at least one line.") List<@Valid PricingLine> items,
            @DecimalMin(value = "0", message = "Use 0 or more.") @DecimalMax(MAX_AMOUNT) BigDecimal printingCost,
            @NotNull @Size(max = 20) List<@Valid AdditionalCost> additionalCosts,
            @DecimalMin(value = "0", message = "Use 0 or more.") @DecimalMax(MAX_AMOUNT) BigDecimal deliveryCost,
            @DecimalMin(value = "0", message = "Use 0 or more.") @DecimalMax(MAX_AMOUNT) BigDecimal discount,
            @FutureOrPresent(message = "Choose today or a later date.") LocalDate expiresOn,
            @Size(max = 2000, message = "Keep the note under 2,000 characters.") String staffNote) {
    }

    public record PricingLine(
            @NotBlank(message = "Describe the line.") @Size(max = 300) String description,
            @Min(value = 1, message = "Enter a quantity.") @Max(1_000_000) int quantity,
            @NotNull(message = "Enter a unit price.") @DecimalMin(value = "0", message = "Use 0 or more.")
            @DecimalMax(MAX_AMOUNT) BigDecimal unitPrice) {
    }

    public record AdditionalCost(
            @NotBlank(message = "Name the cost.") @Size(max = 120) String label,
            @NotNull @DecimalMin(value = "0", message = "Use 0 or more.") @DecimalMax(MAX_AMOUNT) BigDecimal amount) {
    }

    /** RecordedQuoteDecision: the customer's answer, as staff heard it. */
    public record RecordedDecision(
            boolean accepted,
            @NotNull @Pattern(regexp = "phone|telegram|email|in-person") String channel,
            @Size(max = 2000) String note) {
    }

    // ---- Orders -------------------------------------------------------------

    /** OrderStatusChange: {@code note} is shown to the customer under the new stage. */
    public record OrderStatusChange(
            @NotNull @Pattern(regexp = "approved|payment-pending|in-production|quality-control|ready|shipping|delivered|completed|cancelled",
                    message = "Choose a stage.") String status,
            @Size(max = 1000, message = "Keep the note under 1,000 characters.") String note,
            boolean notifyCustomer) {
    }

    /** RecordedPayment: {@code method} is free text ("Cash", "ABA transfer"). */
    public record RecordedPayment(
            @NotNull(message = "Enter the amount.") @DecimalMin(value = "0.01", message = "Enter more than 0.")
            @DecimalMax(MAX_AMOUNT) BigDecimal amount,
            @NotBlank(message = "Say how it was paid.") @Size(max = 80) String method,
            @NotNull(message = "Enter the date it was received.")
            @PastOrPresent(message = "The date cannot be in the future.") LocalDate receivedOn,
            @Size(max = 120) String reference) {
    }

    /** OrderScheduleUpdate. */
    public record OrderSchedule(LocalDate dueDate, @Valid Delivery delivery) {

        public record Delivery(
                @Pattern(regexp = "pickup|delivery", message = "Choose pickup or delivery.") String method,
                @Size(max = 1000) String address,
                @Size(max = 120) String contactName,
                @Size(max = 40) String contactPhone,
                LocalDate estimatedDate,
                @Size(max = 120) String trackingReference,
                @Size(max = 2000) String instructions) {
        }
    }

    // ---- Artwork, customers, staff ------------------------------------------

    /** DesignReviewDecision: {@code reviewNote} is shown to the customer; required when rejecting. */
    public record DesignReview(
            @NotNull @Pattern(regexp = "approved|rejected", message = "Approve or ask for a new file.") String status,
            @Size(max = 1000, message = "Keep the note under 1,000 characters.") String reviewNote) {
    }

    /** AccountStatusChange: a reason is required when suspending. */
    public record AccountStatusChange(
            @NotNull @Pattern(regexp = "active|suspended", message = "Choose active or suspended.") String status,
            @Size(max = 500, message = "Keep the reason under 500 characters.") String reason) {
    }

    /** StaffInvite. The backend emails the invitation; nobody chooses the password for them. */
    public record StaffInvite(
            @NotBlank(message = "Enter their name.") @Size(max = 150) String fullName,
            @NotBlank(message = "Enter their email address.") @Email(message = "Enter a valid email address.")
            @Size(max = 255) String email,
            @NotNull @Size(min = 1, message = "Choose at least one role.")
            List<@Pattern(regexp = "owner", message = "Choose Owner (admin): it is the only staff role.") String> roles) {
    }

    public record StaffRoles(
            @NotNull @Size(min = 1, message = "Choose at least one role.")
            List<@Pattern(regexp = "owner", message = "Choose Owner (admin): it is the only staff role.") String> roles) {
    }

    public record StaffStatus(@NotNull @Pattern(regexp = "active|disabled") String status) {
    }

    /** A staff-only remark on an order or a customer. */
    public record OrderNote(
            @NotBlank(message = "Write the note.") @Size(max = 2000, message = "Keep the note under 2,000 characters.")
            String body) {
    }
}
