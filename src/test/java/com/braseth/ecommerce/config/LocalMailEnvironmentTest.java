package com.braseth.ecommerce.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class LocalMailEnvironmentTest {

    @TempDir
    Path dir;

    private Path dotEnv() throws IOException {
        return Files.writeString(dir.resolve(".env"), """
                # comment
                DB_USERNAME=postgres
                MAIL_USERNAME=shop@example.com
                MAIL_PASSWORD=app password with spaces
                TELEGRAM_BOT_TOKEN=123456:ABC-def
                TELEGRAM_CHAT_ID=-1001234567890

                JWT_SECRET=not-for-local
                """);
    }

    private StandardEnvironment environment(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void localProfileReadsOnlyTheMailAndTelegramLines() throws IOException {
        StandardEnvironment environment = environment("local");
        new LocalMailEnvironment().load(environment, dotEnv());

        assertThat(environment.getProperty("MAIL_USERNAME")).isEqualTo("shop@example.com");
        assertThat(environment.getProperty("MAIL_PASSWORD")).isEqualTo("app password with spaces");
        assertThat(environment.getProperty("TELEGRAM_BOT_TOKEN")).isEqualTo("123456:ABC-def");
        assertThat(environment.getProperty("TELEGRAM_CHAT_ID")).isEqualTo("-1001234567890");
        assertThat(environment.getProperty("DB_USERNAME")).isNull();
        assertThat(environment.getProperty("JWT_SECRET")).isNull();
    }

    @Test
    void otherProfilesIgnoreTheFile() throws IOException {
        StandardEnvironment environment = environment("prod");
        new LocalMailEnvironment().load(environment, dotEnv());

        assertThat(environment.getProperty("MAIL_USERNAME")).isNull();
    }

    @Test
    void realEnvironmentVariablesWin() throws IOException {
        StandardEnvironment environment = environment("local");
        environment.getPropertySources().addFirst(new MapPropertySource("real", Map.of("MAIL_USERNAME", "real@example.com")));
        new LocalMailEnvironment().load(environment, dotEnv());

        assertThat(environment.getProperty("MAIL_USERNAME")).isEqualTo("real@example.com");
        assertThat(environment.getProperty("MAIL_PASSWORD")).isEqualTo("app password with spaces");
    }

    @Test
    void noFileDoesNothing() {
        StandardEnvironment environment = environment("local");
        new LocalMailEnvironment().load(environment, dir.resolve("missing.env"));

        assertThat(environment.getProperty("MAIL_USERNAME")).isNull();
    }
}
