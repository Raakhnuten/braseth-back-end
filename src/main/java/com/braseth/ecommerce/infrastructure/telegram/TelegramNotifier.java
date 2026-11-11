package com.braseth.ecommerce.infrastructure.telegram;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.braseth.ecommerce.config.BrasethProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Posts short alerts to the staff Telegram chat through the Bot API.
 *
 * Fire and forget: the caller has already saved what the message is about,
 * so a slow or failing Telegram is logged and never reaches the customer.
 * With no bot token or chat id configured, nothing is sent.
 */
@Slf4j
@Service
public class TelegramNotifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final BrasethProperties.Telegram settings;
    private final WebClient client;

    @Autowired
    TelegramNotifier(BrasethProperties properties, WebClient.Builder builder) {
        this(properties.telegram(), builder, "https://api.telegram.org");
    }

    TelegramNotifier(BrasethProperties.Telegram settings, WebClient.Builder builder, String apiUrl) {
        this.settings = settings;
        this.client = builder.baseUrl(apiUrl).build();
        if (!settings.enabled()) {
            log.info("Telegram alerts are off: set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID to turn them on");
        }
    }

    /** Sends {@code html} (Telegram's HTML subset) in the background. */
    public void send(String html) {
        post(html).subscribe();
    }

    /** The request itself; completes empty on success, failure or when turned off. */
    Mono<Void> post(String html) {
        if (!settings.enabled()) {
            return Mono.empty();
        }
        return client.post()
                // Not a template variable: that would encode the token's ':' as %3A.
                .uri("/bot" + settings.botToken().strip() + "/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "chat_id", settings.chatId().strip(),
                        "text", html,
                        "parse_mode", "HTML",
                        "disable_web_page_preview", true))
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .then()
                // The token is part of the URL, so log only the reason, never the request.
                .onErrorResume(error -> {
                    log.warn("Telegram alert not sent: {}", reason(error));
                    return Mono.empty();
                });
    }

    private static String reason(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            return response.getStatusCode() + " " + response.getResponseBodyAsString();
        }
        return error.getClass().getSimpleName();
    }

    /** Escapes text for Telegram's HTML parse mode, attribute values included. */
    public static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
