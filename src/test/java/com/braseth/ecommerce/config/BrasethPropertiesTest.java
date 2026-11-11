package com.braseth.ecommerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class BrasethPropertiesTest {

    private static BrasethProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("braseth.v1", BrasethProperties.class);
    }

    @Test
    void defaultsMatchThePreviousHardCodedValues() {
        BrasethProperties properties = bind(Map.of());

        assertThat(properties.mail().logOnly()).isFalse();
        assertThat(properties.designs().maxFileSizeBytes()).isEqualTo(26_214_400L);
        assertThat(properties.designs().maxFilesPerRequest()).isEqualTo(5);
        assertThat(properties.designs().bucket()).isEqualTo("customer-artwork");
        assertThat(properties.designs().linkMinutes()).isEqualTo(15);
        assertThat(properties.telegram().enabled()).isFalse();
    }

    @Test
    void telegramIsOnOnlyWithBothValues() {
        assertThat(bind(Map.of("braseth.v1.telegram.bot-token", "123:abc")).telegram().enabled()).isFalse();
        assertThat(bind(Map.of(
                "braseth.v1.telegram.bot-token", "123:abc",
                "braseth.v1.telegram.chat-id", "-10042")).telegram().enabled()).isTrue();
    }

    @Test
    void readsTheExistingPropertyKeys() {
        BrasethProperties properties = bind(Map.of(
                "braseth.v1.mail.log-only", "true",
                "braseth.v1.designs.max-files-per-request", "3"));

        assertThat(properties.mail().logOnly()).isTrue();
        assertThat(properties.designs().maxFilesPerRequest()).isEqualTo(3);
        assertThat(properties.designs().bucket()).isEqualTo("customer-artwork");
    }
}
