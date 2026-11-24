import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useApiClient } from "@/api/api-client-context";
import { unwrap, type Schemas } from "@/api/client";
import { isRetryable } from "@/api/problem";
import { queryKeys, type PageParams } from "@/api/query-keys";

export function useOrders(params: PageParams & { status?: Schemas["OrderStatus"] }) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.orders.list(params),
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/orders", { params: { query: params }, signal })),
    placeholderData: keepPreviousData,
  });
}

export function useOrder(orderId: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.orders.detail(orderId),
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/orders/{orderId}", { params: { path: { orderId } }, signal })),
  });
}

export interface CreateOrderVariables {
  body: Schemas["CreateOrderRequest"];
  /** Fixed per submission attempt, so every retry of the same submission is deduplicated server-side. */
  idempotencyKey: string;
}

export function useCreateOrder() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, idempotencyKey }: CreateOrderVariables) =>
      unwrap(
        await api.POST("/api/v1/orders", { params: { header: { "Idempotency-Key": idempotencyKey } }, body }),
      ),
    // Safe only because the retry reuses the same Idempotency-Key.
    retry: (failureCount, error) => failureCount < 2 && isRetryable(error),
    onSuccess: async (order) => {
      queryClient.setQueryData(queryKeys.orders.detail(order.id), order);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.orders.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.approvals.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.inventory.all }),
      ]);
    },
  });
}
