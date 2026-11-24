package com.materialflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/** JWT security is on, so these also prove preflights are answered before authentication. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "materialflow.security.enabled=true",
        "materialflow.gateway.cors.allowed-origins=http://localhost:5173",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/unused/certs"
})
class GatewayCorsIntegrationTest {
    private static final String CONSOLE = "http://localhost:5173";

    @LocalServerPort int port;
    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void answersPreflightForTheConfiguredOriginWithoutAToken() {
        client.options().uri("/api/v1/orders")
                .header(HttpHeaders.ORIGIN, CONSOLE)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        headers -> assertThat(headers.toLowerCase()).contains("authorization", "idempotency-key"));
    }

    @Test
    void rejectsPreflightFromAnyOtherOrigin() {
        client.options().uri("/api/v1/orders")
                .header(HttpHeaders.ORIGIN, "https://attacker.example.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void routesConsoleReadEndpointsBehindAuthentication() {
        for (String path : new String[] {"/api/v1/orders", "/api/v1/inventory/availability", "/api/v1/customers",
                "/api/v1/materials", "/api/v1/warehouses", "/api/v1/approvals"}) {
            client.get().uri(path).header(HttpHeaders.ORIGIN, CONSOLE).exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE);
        }
    }
}
