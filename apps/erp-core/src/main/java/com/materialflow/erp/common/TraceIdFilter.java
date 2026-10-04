package com.materialflow.erp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{8,64}")
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        MDC.put("requestId", UUID.randomUUID().toString());
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("requestId");
        }
    }

    public static String currentTraceId() {
        String value = MDC.get("traceId");
        return value == null ? "local-" + UUID.randomUUID().toString().replace("-", "") : value;
    }
}

