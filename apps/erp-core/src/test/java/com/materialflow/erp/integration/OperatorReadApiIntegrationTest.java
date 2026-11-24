package com.materialflow.erp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the operator read API over real HTTP with JWT security enabled: signed tokens go through
 * the production filter chain and realm-role converter, so tenant and role checks are the deployed ones.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorReadApiIntegrationTest {
    private static final KeyPair SIGNING_KEYS = rsaKeyPair();
    private static final String ORGANIZATION = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORGANIZATION = "99999999-9999-9999-9999-999999999999";
    private static final String WAREHOUSE = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_WAREHOUSE = "99999999-9999-9999-9999-999999999997";
    private static final String CUSTOMER = "33333333-3333-3333-3333-333333333333";
    private static final String OTHER_CUSTOMER = "99999999-9999-9999-9999-999999999996";
    private static final String CEMENT = "55555555-5555-5555-5555-555555555555";
    private static final String CEMENT_LOCATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String SALES_USER = "66666666-6666-6666-6666-666666666601";
    private static final String APPROVER_USER = "66666666-6666-6666-6666-666666666602";
    private static final String READONLY_USER = "66666666-6666-6666-6666-666666666603";
    private static final String OTHER_USER = "99999999-9999-9999-9999-999999999998";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("materialflow")
            .withUsername("materialflow")
            .withPassword("materialflow");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("materialflow.security.enabled", () -> "true");
    }

    @TestConfiguration
    static class TestTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEYS.getPublic()).build();
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String writer = token(SALES_USER, ORGANIZATION, List.of(WAREHOUSE), "SALES_ORDER_WRITE");
    private final String approver = token(APPROVER_USER, ORGANIZATION, List.of(WAREHOUSE), "APPROVAL_DECIDE");
    private final String readonly = token(READONLY_USER, ORGANIZATION, List.of(WAREHOUSE));
    private final String otherTenant = token(OTHER_USER, OTHER_ORGANIZATION, List.of(OTHER_WAREHOUSE),
            "SALES_ORDER_WRITE", "APPROVAL_DECIDE");

    @BeforeAll
    static void otherTenantFixture(@Autowired JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO organizations (id, code, name) VALUES (?::uuid, 'MF-OTHER', 'Other Synthetic Tenant')",
                OTHER_ORGANIZATION);
        jdbc.update("""
                INSERT INTO app_users (id, organization_id, external_subject, display_name)
                VALUES (?::uuid, ?::uuid, 'other-sales', 'Other Tenant Sales')
                """, OTHER_USER, OTHER_ORGANIZATION);
        jdbc.update("""
                INSERT INTO customers (id, organization_id, code, name, region_code)
                VALUES (?::uuid, ?::uuid, 'CUST-001', 'Other Tenant Customer', 'EAST')
                """, OTHER_CUSTOMER, OTHER_ORGANIZATION);
        jdbc.update("""
                INSERT INTO warehouses (id, organization_id, code, name, region_code)
                VALUES (?::uuid, ?::uuid, 'WH-EAST-01', 'Other Tenant Warehouse', 'EAST')
                """, OTHER_WAREHOUSE, OTHER_ORGANIZATION);
        jdbc.update("""
                INSERT INTO sales_orders
                    (id, organization_id, order_number, customer_id, applicant_id, status,
                     allow_partial_fulfillment, total_amount, version)
                VALUES (?, ?::uuid, 'SO-OTHER-TENANT-0001', ?::uuid, ?::uuid, 'RESERVED', true, 10.00, 1)
                """, UUID.randomUUID(), OTHER_ORGANIZATION, OTHER_CUSTOMER, OTHER_USER);
    }

    @Test
    void ordersArePagedNewestFirstAndNeverCrossTenants() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(createSmallOrder(writer).path("id").asString());
        }

        JsonNode first = get("/api/v1/orders?size=2", writer).body();
        JsonNode second = get("/api/v1/orders?size=2&page=1", writer).body();
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        long organizationOrders = jdbc.queryForObject(
                "SELECT count(*) FROM sales_orders WHERE organization_id = ?::uuid", Long.class, ORGANIZATION);
        assertThat(first.path("totalItems").asLong()).isEqualTo(organizationOrders);
        List<String> firstIds = ids(first);
        assertThat(firstIds).hasSize(2).doesNotContainAnyElementsOf(ids(second));
        assertThat(firstIds).containsExactly(created.get(2), created.get(1));
        assertThat(ids(second)).first().isEqualTo(created.get(0));
        assertThat(first.path("items").get(0).path("customerName").asString()).isEqualTo("Cedar Ridge Contractors");

        // A spoofed organization header must not widen the other tenant's view.
        JsonNode otherView = get("/api/v1/orders?size=100", otherTenant,
                Map.of("X-Organization-Id", ORGANIZATION, "X-Actor-Id", SALES_USER)).body();
        assertThat(otherView.path("totalItems").asLong()).isEqualTo(1);
        assertThat(otherView.path("items").get(0).path("orderNumber").asString()).isEqualTo("SO-OTHER-TENANT-0001");
        assertThat(ids(otherView)).doesNotContainAnyElementsOf(created);
        assertThat(get("/api/v1/orders/" + created.getFirst(), otherTenant).status()).isEqualTo(404);
    }

    @Test
    void statusFilterAndPageBoundsAreValidated() throws Exception {
        JsonNode order = createSmallOrder(writer);
        String status = order.path("status").asString();

        JsonNode filtered = get("/api/v1/orders?size=100&status=" + status, writer).body();
        assertThat(ids(filtered)).contains(order.path("id").asString());
        filtered.path("items").forEach(item -> assertThat(item.path("status").asString()).isEqualTo(status));
        assertThat(get("/api/v1/orders?status=CANCELLED", writer).body().path("totalItems").asLong()).isZero();

        for (String query : List.of("size=101", "size=0", "page=-1", "status=NOT_A_STATUS")) {
            Response response = get("/api/v1/orders?" + query, writer);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.body().path("errorCode").asString()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void readOnlyUserCanReadEveryListButCannotCreateOrders() throws Exception {
        for (String path : List.of("/api/v1/orders", "/api/v1/approvals", "/api/v1/inventory/availability",
                "/api/v1/customers", "/api/v1/materials", "/api/v1/warehouses")) {
            assertThat(get(path, readonly).status()).as(path).isEqualTo(200);
        }

        Response denied = post("/api/v1/orders", readonly, smallOrderBody(), UUID.randomUUID().toString());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().path("errorCode").asString()).isEqualTo("ACCESS_DENIED");
        assertThat(get("/api/v1/orders", null).status()).isEqualTo(401);
    }

    @Test
    void approvalQueueMarksOnlyTheAssignedCheckerAsDecider() throws Exception {
        String body = """
                {"customerId":"%s","allowPartialFulfillment":true,"lines":[{"materialId":"%s",
                 "warehouseId":"%s","locationId":"%s","quantity":3000}]}
                """.formatted(CUSTOMER, CEMENT, WAREHOUSE, CEMENT_LOCATION);
        Response created = post("/api/v1/orders", writer, body, UUID.randomUUID().toString());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("status").asString()).isEqualTo("PENDING_APPROVAL");
        String approvalId = created.body().path("approvalInstanceId").asString();

        assertThat(queueEntry(approver, approvalId).path("decidableByActor").asBoolean()).isTrue();
        assertThat(queueEntry(writer, approvalId).path("decidableByActor").asBoolean()).isFalse();
        assertThat(queueEntry(readonly, approvalId).path("decidableByActor").asBoolean()).isFalse();
        JsonNode pending = get("/api/v1/approvals/" + approvalId, approver).body();
        assertThat(pending.path("approval").path("orderNumber").asString())
                .isEqualTo(created.body().path("orderNumber").asString());
        assertThat(pending.path("decisions").isEmpty()).isTrue();

        Response decided = post("/api/v1/approvals/" + approvalId + "/decisions", approver,
                "{\"decision\":\"APPROVE\"}", UUID.randomUUID().toString());
        assertThat(decided.status()).isEqualTo(200);

        JsonNode approved = get("/api/v1/approvals/" + approvalId, approver).body();
        assertThat(approved.path("approval").path("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("approval").path("decidableByActor").asBoolean()).isFalse();
        assertThat(approved.path("decisions").get(0).path("approverId").asString()).isEqualTo(APPROVER_USER);
        assertThat(get("/api/v1/approvals?status=PENDING&size=100", approver).body().path("items").valueStream()
                .map(item -> item.path("approvalId").asString())).doesNotContain(approvalId);
        assertThat(get("/api/v1/approvals/" + approvalId, otherTenant).status()).isEqualTo(404);
    }

    @Test
    void inventoryAndReferenceDataFollowTenantAndWarehouseScope() throws Exception {
        JsonNode stock = get("/api/v1/inventory/availability?size=100", writer).body();
        assertThat(stock.path("items").valueStream().map(item -> item.path("sku").asString()))
                .contains("CEM-42-5", "PLY-18-2440");
        stock.path("items").forEach(item -> assertThat(item.path("available").decimalValue())
                .isEqualByComparingTo(item.path("onHand").decimalValue()
                        .subtract(item.path("reserved").decimalValue())));
        assertThat(get("/api/v1/inventory/availability?warehouseId=" + OTHER_WAREHOUSE, writer).status())
                .isEqualTo(403);
        assertThat(get("/api/v1/inventory/availability", otherTenant).body().path("totalItems").asLong()).isZero();

        assertThat(get("/api/v1/customers?q=cedar", writer).body().path("items").valueStream()
                .map(item -> item.path("id").asString())).containsExactly(CUSTOMER);
        assertThat(get("/api/v1/customers?size=100", otherTenant).body().path("items").valueStream()
                .map(item -> item.path("id").asString())).containsExactly(OTHER_CUSTOMER);
        assertThat(get("/api/v1/materials?q=PLY", writer).body().path("items").get(0).path("sku").asString())
                .isEqualTo("PLY-18-2440");
        assertThat(get("/api/v1/warehouses", otherTenant).body().path("items").valueStream()
                .map(item -> item.path("id").asString())).containsExactly(OTHER_WAREHOUSE);
    }

    private JsonNode queueEntry(String token, String approvalId) throws Exception {
        return get("/api/v1/approvals?status=PENDING&size=100", token).body().path("items").valueStream()
                .filter(item -> item.path("approvalId").asString().equals(approvalId))
                .findFirst().orElseThrow();
    }

    private JsonNode createSmallOrder(String token) throws Exception {
        Response response = post("/api/v1/orders", token, smallOrderBody(), UUID.randomUUID().toString());
        assertThat(response.status()).isEqualTo(201);
        return response.body();
    }

    private String smallOrderBody() {
        return """
                {"customerId":"%s","allowPartialFulfillment":true,"lines":[{"materialId":"%s",
                 "warehouseId":"%s","locationId":"%s","quantity":0.001}]}
                """.formatted(CUSTOMER, CEMENT, WAREHOUSE, CEMENT_LOCATION);
    }

    private static List<String> ids(JsonNode page) {
        return page.path("items").valueStream().map(item -> item.path("id").asString()).toList();
    }

    private Response get(String path, String token) throws IOException, InterruptedException {
        return get(path, token, Map.of());
    }

    private Response get(String path, String token, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        headers.forEach(request::header);
        return send(request, token);
    }

    private Response post(String path, String token, String body, String idempotencyKey)
            throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)), token);
    }

    private Response send(HttpRequest.Builder request, String token) throws IOException, InterruptedException {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body().isBlank() ? objectMapper.missingNode() : objectMapper.readTree(response.body());
        return new Response(response.statusCode(), body);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String token(String userId, String organizationId, List<String> warehouses, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://synthetic-issuer.invalid/realms/materialflow")
                .subject("synthetic-" + userId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("user_id", userId)
                .claim("organization_id", organizationId)
                .claim("warehouse_scope", warehouses)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(SIGNING_KEYS.getPrivate()));
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
        return jwt.serialize();
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Response(int status, JsonNode body) {
    }
}
