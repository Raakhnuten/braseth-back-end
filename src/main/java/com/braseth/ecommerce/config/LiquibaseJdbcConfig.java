package com.braseth.ecommerce.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/** The JDBC DataSource Liquibase runs migrations through; the app itself uses R2DBC. */
@Configuration
public class LiquibaseJdbcConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties liquibaseDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource liquibaseDataSource(DataSourceProperties liquibaseDataSourceProperties) {
        return liquibaseDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}