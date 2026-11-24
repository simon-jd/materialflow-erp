package com.materialflow.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class GatewaySecurityConfiguration {
    @Bean
    SecurityWebFilterChain security(
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            @Value("${materialflow.security.enabled:false}") boolean securityEnabled) {
        // CORS runs before authentication so that browser preflights (which carry no token) are answered.
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource));
        if (securityEnabled) {
            http.authorizeExchange(exchange -> exchange
                            .pathMatchers("/actuator/health/**").permitAll()
                            .anyExchange().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { }));
        } else {
            http.authorizeExchange(exchange -> exchange.anyExchange().permitAll());
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (properties.allowedOrigins().isEmpty()) {
            return source;
        }
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.allowedOrigins());
        api.setAllowedMethods(List.of("GET", "POST"));
        api.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id",
                "traceparent"));
        api.setExposedHeaders(List.of("X-Request-Id", "X-Trace-Id"));
        // Bearer tokens travel in a header, never in cookies.
        api.setAllowCredentials(false);
        api.setMaxAge(Duration.ofMinutes(10));
        source.registerCorsConfiguration("/api/**", api);
        return source;
    }
}
