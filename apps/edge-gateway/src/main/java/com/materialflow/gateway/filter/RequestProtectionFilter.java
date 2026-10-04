package com.materialflow.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

@Component
public class RequestProtectionFilter implements GlobalFilter, Ordered {
    private static final Set<HttpMethod> SENSITIVE_WRITES = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private final ReactiveStringRedisTemplate redis;

    public RequestProtectionFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        String requestId = supplied == null || supplied.isBlank() || supplied.length() > 64
                ? UUID.randomUUID().toString() : supplied;
        String generatedTraceParent = "00-" + UUID.randomUUID().toString().replace("-", "")
                + "-0000000000000001-01";
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        exchange.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponse().getHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponse().getHeaders().set("Cache-Control", "no-store");
        ServerWebExchange enriched = exchange.mutate().request(request -> request.headers(headers -> {
            headers.set("X-Request-Id", requestId);
            headers.set("traceparent", headers.getFirst("traceparent") == null
                    ? generatedTraceParent
                    : headers.getFirst("traceparent"));
        })).build();
        if (!SENSITIVE_WRITES.contains(exchange.getRequest().getMethod())) {
            return chain.filter(enriched);
        }
        return redis.opsForValue().get("materialflow:gateway:availability-probe")
                .timeout(Duration.ofMillis(250))
                .thenReturn(true)
                .onErrorReturn(false)
                .flatMap(available -> available ? chain.filter(enriched) : unavailable(exchange, requestId));
    }

    private Mono<Void> unavailable(ServerWebExchange exchange, String requestId) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error_code\":\"SHARED_RATE_LIMITER_UNAVAILABLE\","
                + "\"message\":\"Write protection is temporarily unavailable\","
                + "\"traceId\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
