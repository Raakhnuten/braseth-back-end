package com.braseth.ecommerce.modules.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What the customer area sends (customer.model.ts, customer.service.ts, quote.model.ts). */
public final class AccountBodies {

    private AccountBodies() {
    }

    public record AddressBody(
            @NotBlank(message = "Enter the street address.") @Size(max = 255) String line1,
            @Size(max = 255) String line2,
            @NotBlank(message = "Enter the city.") @Size(max = 100) String city,
            @Size(max = 100) String province,
            @Size(max = 20) String postalCode,
            @NotBlank(message = "Enter the country.") @Size(max = 100) String country) {
    }

    public record ProfileBody(
            @NotBlank(message = "Enter your name.") @Size(max = 120, message = "Keep your name under 120 characters.") String fullName,
            @NotBlank(message = "Enter your phone number.") @Size(max = 40) String phone,
            @Size(max = 80) String telegram,
            @NotNull(message = "Choose how we should reach you.") @Pattern(regexp = "email|phone|telegram",
                    message = "Choose how we should reach you.") String preferredContactMethod,
            @Valid AddressBody address) {
    }

    public record CompanyBody(
            @NotBlank(message = "Enter the company name.") @Size(max = 200) String name,
            @Valid AddressBody address,
            @Size(max = 120) String contactPersonName,
            @Size(max = 120) String contactPersonRole,
            @Email(message = "Enter a valid email.") @Size(max = 255) String contactEmail,
            @Size(max = 40) String contactPhone,
            @Size(max = 60) String taxId,
            @Size(max = 120) String industry,
            @Size(max = 255) @Pattern(regexp = "^$|^https?://.+", message = "Start the website with https://") String website) {
    }

    public record SettingsBody(
            @NotNull @Pattern(regexp = "email|phone|telegram") String channel,
            boolean quoteUpdates,
            boolean orderUpdates,
            boolean designUpdates,
            boolean marketing) {
    }

    public record DecisionBody(boolean accepted, @Size(max = 2000) String note) {
    }
}
