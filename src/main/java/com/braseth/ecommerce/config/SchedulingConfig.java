package com.braseth.ecommerce.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (e.g. the nightly token cleanup). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
