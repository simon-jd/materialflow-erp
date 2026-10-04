package com.materialflow.contracts.events;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String errorCode,
        String message,
        String traceId,
        Instant timestamp,
        Map<String, String> fieldErrors) {
}

