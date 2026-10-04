package com.materialflow.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfiguration {
    @Bean
    KeyResolver actorOrAddressKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "subject:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress address = exchange.getRequest().getRemoteAddress();
                    return "address:" + (address == null ? "unknown" : address.getAddress().getHostAddress());
                }));
    }
}
