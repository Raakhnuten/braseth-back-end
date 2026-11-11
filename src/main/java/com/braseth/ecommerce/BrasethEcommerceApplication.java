package com.braseth.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The app runs on R2DBC; the JDBC DataSource exists only for Liquibase, so the
 * JDBC transaction manager is left out to keep R2DBC's the only one. Users
 * sign in with JWTs, so Spring's generated in-memory user is left out too.
 */
@SpringBootApplication(exclude = {
        DataSourceTransactionManagerAutoConfiguration.class,
        ReactiveUserDetailsServiceAutoConfiguration.class })
@ConfigurationPropertiesScan
public class BrasethEcommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrasethEcommerceApplication.class, args);
    }
}
