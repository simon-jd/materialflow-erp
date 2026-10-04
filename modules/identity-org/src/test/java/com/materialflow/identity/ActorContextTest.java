package com.materialflow.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActorContextTest {
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Test
    void enforcesRoleOrganizationAndWarehouseScopes() {
        ActorContext actor = new ActorContext(UUID.randomUUID(), ORGANIZATION, Set.of("ORDER_WRITE"),
                Set.of(WAREHOUSE));

        actor.requireRole("ORDER_WRITE");
        actor.requireOrganization(ORGANIZATION);
        actor.requireWarehouse(WAREHOUSE);
        assertThatThrownBy(() -> actor.requireRole("APPROVAL_DECIDE"))
                .isInstanceOf(ActorContext.AccessDeniedException.class);
        assertThatThrownBy(() -> actor.requireOrganization(UUID.randomUUID()))
                .isInstanceOf(ActorContext.AccessDeniedException.class);
        assertThatThrownBy(() -> actor.requireWarehouse(UUID.randomUUID()))
                .isInstanceOf(ActorContext.AccessDeniedException.class);
    }

    @Test
    void emptyWarehouseScopeMeansOrganizationWideAccessAndInputsAreCopied() {
        var mutableRoles = new java.util.HashSet<>(Set.of("ORDER_READ"));
        ActorContext actor = new ActorContext(UUID.randomUUID(), ORGANIZATION, mutableRoles, null);
        mutableRoles.clear();

        actor.requireRole("ORDER_READ");
        actor.requireWarehouse(UUID.randomUUID());
        assertThatThrownBy(() -> actor.roles().add("ESCALATED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
