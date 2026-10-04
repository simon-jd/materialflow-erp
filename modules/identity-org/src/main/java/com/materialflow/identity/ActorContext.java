package com.materialflow.identity;

import java.util.Set;
import java.util.UUID;

public record ActorContext(
        UUID userId,
        UUID organizationId,
        Set<String> roles,
        Set<UUID> warehouseScope) {

    public ActorContext {
        if (userId == null || organizationId == null) {
            throw new IllegalArgumentException("userId and organizationId are required");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        warehouseScope = warehouseScope == null ? Set.of() : Set.copyOf(warehouseScope);
    }

    public void requireRole(String role) {
        if (!roles.contains(role)) {
            throw new AccessDeniedException("Required role is missing: " + role);
        }
    }

    public void requireOrganization(UUID requestedOrganizationId) {
        if (!organizationId.equals(requestedOrganizationId)) {
            throw new AccessDeniedException("Organization scope mismatch");
        }
    }

    public void requireWarehouse(UUID warehouseId) {
        if (!warehouseScope.isEmpty() && !warehouseScope.contains(warehouseId)) {
            throw new AccessDeniedException("Warehouse is outside actor scope");
        }
    }

    public static final class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }
}

