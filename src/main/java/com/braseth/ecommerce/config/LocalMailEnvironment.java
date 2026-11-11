package com.braseth.ecommerce.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Lets a developer run the {@code local} profile with the shop's mail account,
 * staff Telegram bot and reCAPTCHA secret from the git-ignored {@code .env}
 * file, without exporting variables first.
 *
 * Only the mail, Telegram and reCAPTCHA lines ({@code MAIL_USERNAME},
 * {@code MAIL_PASSWORD}, {@code TELEGRAM_BOT_TOKEN}, {@code TELEGRAM_CHAT_ID},
 * {@code RECAPTCHA_SECRET}) are read: the rest of
 * that file (database, MinIO, JWT) is for other setups and would point the
 * local profile at the wrong database. Real environment variables still win,
 * and outside the local profile, or without a {@code .env}, this does nothing.
 */
public class LocalMailEnvironment implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> KEYS = Set.of("MAIL_USERNAME", "MAIL_PASSWORD", "TELEGRAM_BOT_TOKEN",
            "TELEGRAM_CHAT_ID", "RECAPTCHA_SECRET");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        load(environment, Path.of(".env"));
    }

    void load(ConfigurableEnvironment environment, Path file) {
        if (!environment.matchesProfiles("local") || !Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (line.isBlank() || line.stripLeading().startsWith("#") || equals < 1) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (KEYS.contains(key) && !value.isEmpty() && environment.getProperty(key) == null) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            return;
        }
        if (!values.isEmpty()) {
            // Last: anything set in the real environment or on the command line wins.
            environment.getPropertySources().addLast(new MapPropertySource("localMailFromDotEnv", values));
        }
    }

    /** After the config files, so the active profiles are known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
