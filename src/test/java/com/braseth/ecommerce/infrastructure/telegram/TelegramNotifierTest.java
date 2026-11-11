package com.braseth.ecommerce.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.braseth.ecommerce.config.BrasethProperties;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TelegramNotifierTest {

    private final List<ClientRequest> requests = new ArrayList<>();

    private TelegramNotifier notifier(String token, String chatId, HttpStatus answer) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(answer).body("{\"ok\":" + answer.is2xxSuccessful() + "}").build());
        });
        return new TelegramNotifier(new BrasethProperties.Telegram(token, chatId), builder, "https://telegram.test");
    }

    @Test
    void postsToTheBotsSendMessageMethod() {
        StepVerifier.create(notifier("123:abc", "-10042", HttpStatus.OK).post("<b>hi</b>")).verifyComplete();

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method().name()).isEqualTo("POST");
        assertThat(requests.get(0).url().toString()).isEqualTo("https://telegram.test/bot123:abc/sendMessage");
    }

    @Test
    void sendsNothingWhenNotConfigured() {
        StepVerifier.create(notifier("", "-10042", HttpStatus.OK).post("hi")).verifyComplete();
        StepVerifier.create(notifier("123:abc", " ", HttpStatus.OK).post("hi")).verifyComplete();

        assertThat(requests).isEmpty();
    }

    @Test
    void aTelegramErrorIsSwallowed() {
        StepVerifier.create(notifier("123:abc", "-10042", HttpStatus.BAD_REQUEST).post("hi")).verifyComplete();

        assertThat(requests).hasSize(1);
    }

    @Test
    void escapesHtml() {
        assertThat(TelegramNotifier.escape("<i>A & \"B\"</i>")).isEqualTo("&lt;i&gt;A &amp; &quot;B&quot;&lt;/i&gt;");
        assertThat(TelegramNotifier.escape(null)).isEmpty();
    }
}
