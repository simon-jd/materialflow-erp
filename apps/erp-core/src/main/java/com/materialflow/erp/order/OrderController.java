package com.materialflow.erp.order;

import com.materialflow.erp.common.PageResponse;
import com.materialflow.erp.security.ActorContextFactory;
import com.materialflow.erp.reservation.CancelOrderRequest;
import com.materialflow.erp.reservation.ReservationReleaseApplicationService;
import com.materialflow.sales.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderApplicationService service;
    private final OrderQueryApplicationService queries;
    private final ReservationReleaseApplicationService releases;
    private final ActorContextFactory actors;

    public OrderController(OrderApplicationService service, OrderQueryApplicationService queries,
            ReservationReleaseApplicationService releases, ActorContextFactory actors) {
        this.service = service;
        this.queries = queries;
        this.releases = releases;
        this.actors = actors;
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.list(status, page, size, context);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody CreateOrderRequest request) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return service.create(request, idempotencyKey, context);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(
            @PathVariable UUID orderId,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return service.get(orderId, context);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody CancelOrderRequest request) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return releases.cancel(orderId, request, idempotencyKey, context);
    }
}
