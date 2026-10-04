package com.materialflow.erp.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.materialflow.erp.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyService {
    private static final Duration RETENTION = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public <T> Attempt<T> begin(
            UUID organizationId,
            String operation,
            String key,
            Object request,
            Class<T> responseType) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST,
                    "A non-empty Idempotency-Key of at most 160 characters is required");
        }
        String requestHash = sha256(write(request));
        int inserted = jdbc.update("""
                INSERT INTO idempotency_records
                    (organization_id, operation, idempotency_key, request_hash, status, expires_at)
                VALUES (?, ?, ?, ?, 'PROCESSING', ?)
                ON CONFLICT DO NOTHING
                """, organizationId, operation, key, requestHash, Timestamp.from(Instant.now().plus(RETENTION)));
        if (inserted == 1) {
            return new Attempt<>(false, null, requestHash);
        }

        Existing existing = jdbc.queryForObject("""
                SELECT request_hash, status, response_body::text
                  FROM idempotency_records
                 WHERE organization_id = ? AND operation = ? AND idempotency_key = ?
                 FOR UPDATE
                """, (resultSet, row) -> new Existing(
                        resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)),
                organizationId, operation, key);
        if (existing == null || !existing.requestHash().equals(requestHash)) {
            throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "The Idempotency-Key was already used with a different request");
        }
        if (!"COMPLETED".equals(existing.status()) || existing.responseBody() == null) {
            throw BusinessException.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "The original request is still processing or did not complete");
        }
        return new Attempt<>(true, read(existing.responseBody(), responseType), requestHash);
    }

    public void complete(
            UUID organizationId,
            String operation,
            String key,
            Object response,
            int responseStatus,
            UUID resourceId) {
        int updated = jdbc.update("""
                UPDATE idempotency_records
                   SET status = 'COMPLETED', response_status = ?, response_body = CAST(? AS jsonb),
                       resource_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE organization_id = ? AND operation = ? AND idempotency_key = ? AND status = 'PROCESSING'
                """, responseStatus, write(response), resourceId, organizationId, operation, key);
        if (updated != 1) {
            throw BusinessException.conflict("IDEMPOTENCY_STATE_CONFLICT", "Idempotency state changed concurrently");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize idempotent payload", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize idempotent response", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Attempt<T>(boolean replay, T response, String requestHash) {
    }

    private record Existing(String requestHash, String status, String responseBody) {
    }
}
