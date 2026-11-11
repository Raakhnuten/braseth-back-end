package com.braseth.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Application settings under {@code braseth.v1.*}, checked at startup. */
@Validated
@ConfigurationProperties("braseth.v1")
public record BrasethProperties(@Valid @DefaultValue Mail mail, @Valid @DefaultValue Designs designs,
        @Valid @DefaultValue Media media, @Valid @DefaultValue Telegram telegram,
        @Valid @DefaultValue Recaptcha recaptcha) {

    /**
     * Photos staff upload for the catalogue and site content. Unlike customer
     * artwork they are public, so they go to the team's public bucket and are
     * linked directly.
     *
     * @param bucket       the public MinIO bucket (the team's product-media)
     * @param publicUrl    where browsers reach that bucket's server; in
     *                     production this is the public address, not the
     *                     internal MinIO endpoint
     * @param maxFileSizeBytes largest photo accepted (10 MB)
     * @param publicBucketUrl  optional address that serves the bucket's objects
     *                         at its root (e.g. a Cloudflare R2 public URL);
     *                         when set, photo links are {@code publicBucketUrl/key}
     *                         instead of {@code publicUrl/bucket/key}
     */
    public record Media(
            @DefaultValue("product-media") @NotBlank String bucket,
            @DefaultValue("http://localhost:9000") @NotBlank String publicUrl,
            @DefaultValue("10485760") @Positive long maxFileSizeBytes,
            @DefaultValue("") String publicBucketUrl) {

        /** The address photo keys are appended to, without a trailing slash. */
        public String objectBaseUrl() {
            String base = publicBucketUrl.isBlank()
                    ? publicUrl.replaceAll("/+$", "") + "/" + bucket
                    : publicBucketUrl;
            return base.replaceAll("/+$", "");
        }
    }

    /**
     * @param logOnly write verification / reset links to the log instead of
     *                e-mailing them (the local profile turns this on)
     */
    public record Mail(@DefaultValue("false") boolean logOnly) {
    }

    /**
     * @param maxFileSizeBytes    largest artwork file accepted (25 MB)
     * @param maxFilesPerRequest  artwork files per upload request
     * @param bucket              MinIO bucket that holds customer artwork
     * @param linkMinutes         how long a download link for artwork stays valid
     */
    public record Designs(
            @DefaultValue("26214400") @Positive long maxFileSizeBytes,
            @DefaultValue("5") @Positive int maxFilesPerRequest,
            @DefaultValue("customer-artwork") @NotBlank String bucket,
            @DefaultValue("15") @Positive int linkMinutes) {
    }

    /**
     * The staff Telegram chat that hears about new quote requests. Leave
     * either value empty to turn the messages off.
     *
     * @param botToken the bot's token from @BotFather
     * @param chatId   the chat the bot posts to: a person's id, or a group's
     *                 (negative) id with the bot added to the group
     */
    public record Telegram(@DefaultValue("") String botToken, @DefaultValue("") String chatId) {

        public boolean enabled() {
            return !botToken.isBlank() && !chatId.isBlank();
        }
    }

    /**
     * Google reCAPTCHA (v2 checkbox) on customer sign-in and registration.
     * Leave the secret empty to turn the check off — the web app hides the
     * widget the same way while its site key is unset.
     *
     * @param secret    the secret key paired with the web app's site key
     * @param verifyUrl Google's verification endpoint
     */
    public record Recaptcha(@DefaultValue("") String secret,
            @DefaultValue("https://www.google.com/recaptcha/api/siteverify") @NotBlank String verifyUrl) {

        public boolean enabled() {
            return !secret.isBlank();
        }
    }
}
