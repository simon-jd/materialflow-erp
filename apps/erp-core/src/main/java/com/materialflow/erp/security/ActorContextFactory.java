package com.materialflow.erp.security;

import com.materialflow.erp.common.BusinessException;
import com.materialflow.identity.ActorContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class ActorContextFactory {
    public ActorContext create(
            Authentication authentication,
            String organizationHeader,
            String actorHeader,
            String rolesHeader,
            String warehouseScopeHeader) {
        try {
            if (authentication instanceof JwtAuthenticationToken token) {
                UUID organizationId = uuidClaim(token, "organization_id");
                UUID userId = uuidClaim(token, "user_id");
                Set<String> roles = token.getAuthorities().stream()
                        .map(authority -> authority.getAuthority().replaceFirst("^(ROLE_|SCOPE_)", ""))
                        .collect(Collectors.toUnmodifiableSet());
                return new ActorContext(userId, organizationId, roles,
                        parseJwtUuids(token.getToken().getClaims().get("warehouse_scope")));
            }
            return new ActorContext(UUID.fromString(actorHeader), UUID.fromString(organizationHeader),
                    parseText(rolesHeader), parseUuids(warehouseScopeHeader));
        } catch (RuntimeException exception) {
            throw new BusinessException("INVALID_ACTOR_CONTEXT", HttpStatus.BAD_REQUEST,
                    "Valid actor and organization context is required");
        }
    }

    private UUID uuidClaim(JwtAuthenticationToken token, String name) {
        return UUID.fromString(token.getToken().getClaimAsString(name));
    }

    private Set<String> parseText(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<UUID> parseUuids(String value) {
        Collection<String> values = parseText(value);
        return values.stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    private Set<UUID> parseJwtUuids(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return parseUuids(claim == null ? null : String.valueOf(claim));
    }
}
