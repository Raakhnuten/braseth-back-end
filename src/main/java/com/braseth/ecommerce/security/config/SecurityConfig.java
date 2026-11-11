package com.braseth.ecommerce.security.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;

import com.braseth.ecommerce.common.exception.ProblemBody;
import com.braseth.ecommerce.security.filter.JwtAuthenticationFilter;
import com.braseth.ecommerce.security.jwt.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Two stateless filter chains:
 * <ul>
 * <li>/api/v1/** — the API. Bearer tokens are read by {@link JwtAuthenticationFilter};
 * 401 and 403 come back with a JSON {code, message} body. No CORS here: the
 * web app calls the API through its own origin (the dev proxy locally, the
 * same host in production); {@code CorsConfig} covers direct dev calls.</li>
 * <li>everything else — only health checks and the API docs are open.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Reachable without a token. Everything else under /api/v1 needs one. */
    private static final String[] PUBLIC_POSTS = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            // The public site's forms; open to guests.
            "/api/v1/quote-requests",
            "/api/v1/designs",
            "/api/v1/contact-messages",
            "/api/v1/newsletter-subscriptions",
    };

    /** The public site's reads. */
    private static final String[] PUBLIC_READS = {
            "/api/v1/products",
            "/api/v1/products/**",
            "/api/v1/site-config",
            "/api/v1/services",
            "/api/v1/services/**",
            "/api/v1/portfolio",
            "/api/v1/portfolio/**",
            "/api/v1/designs/upload-policy",
    };

    /** Outside the API: health checks and the OpenAPI docs. */
    private static final String[] OPEN_PATHS = {
            "/actuator/health",
            "/actuator/health/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**",
    };

    private final JwtService jwtService;
    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain apiSecurityWebFilterChain(ServerHttpSecurity http) {
        return stateless(http)
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/**"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, denied) -> write(exchange, HttpStatus.UNAUTHORIZED,
                                new ProblemBody("unauthenticated", "Please sign in to continue.", null)))
                        .accessDeniedHandler((exchange, denied) -> write(exchange, HttpStatus.FORBIDDEN,
                                new ProblemBody("forbidden", "You do not have access to this.", null))))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/api/v1/**").permitAll()
                        .pathMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .pathMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(new JwtAuthenticationFilter(jwtService, db), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityWebFilterChain defaultSecurityWebFilterChain(ServerHttpSecurity http) {
        return stateless(http)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(OPEN_PATHS).permitAll()
                        .anyExchange().denyAll())
                .build();
    }

    /** No sessions, no browser login flows: every request stands on its own token. */
    private static ServerHttpSecurity stateless(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ProblemBody body) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"code\":\"" + body.code() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
