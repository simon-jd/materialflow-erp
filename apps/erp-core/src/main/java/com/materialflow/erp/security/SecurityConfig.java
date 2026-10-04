package com.materialflow.erp.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "materialflow.security.enabled", havingValue = "true")
    SecurityFilterChain jwtSecurity(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            Object scope = jwt.getClaims().get("scope");
            if (scope instanceof String scopes) {
                scopes.lines().flatMap(line -> java.util.Arrays.stream(line.split(" ")))
                        .filter(value -> !value.isBlank())
                        .map(value -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + value))
                        .forEach(authorities::add);
            }
            Object realm = jwt.getClaims().get("realm_access");
            if (realm instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> roles) {
                roles.stream().map(String::valueOf)
                        .map(value -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + value))
                        .forEach(authorities::add);
            }
            return authorities;
        });
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "materialflow.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}

