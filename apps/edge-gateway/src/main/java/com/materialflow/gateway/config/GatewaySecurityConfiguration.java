package com.materialflow.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfiguration {
    @Bean
    SecurityWebFilterChain security(
            ServerHttpSecurity http,
            @Value("${materialflow.security.enabled:false}") boolean securityEnabled) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable);
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
}
