package com.materialflow.erp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.materialflow.erp.common.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ActorContextFactoryTest {
    private final ActorContextFactory factory = new ActorContextFactory();

    @Test
    void jwtClaimsAreAuthoritativeOverSpoofableActorHeaders() {
        UUID user = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID spoofedWarehouse = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .claim("user_id", user.toString())
                .claim("organization_id", organization.toString())
                .claim("warehouse_scope", List.of(warehouse.toString()))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var authentication = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_SALES_ORDER_WRITE")));

        var actor = factory.create(authentication, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "ADMIN", spoofedWarehouse.toString());

        assertThat(actor.userId()).isEqualTo(user);
        assertThat(actor.organizationId()).isEqualTo(organization);
        assertThat(actor.roles()).containsExactly("SALES_ORDER_WRITE");
        assertThat(actor.warehouseScope()).containsExactly(warehouse);
        assertThat(actor.warehouseScope()).doesNotContain(spoofedWarehouse);
    }

    @Test
    void invalidLocalHeadersReturnStableSafeError() {
        assertThatThrownBy(() -> factory.create(null, "not-a-uuid", "also-invalid", "ADMIN", ""))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("INVALID_ACTOR_CONTEXT");
                    assertThat(error.getMessage()).doesNotContain("IllegalArgumentException");
                });
    }
}
