import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useApiClient } from "@/api/api-client-context";
import { unwrap, type Schemas } from "@/api/client";
import { queryKeys, type PageParams } from "@/api/query-keys";

export function useApprovals(params: PageParams & { status?: Schemas["ApprovalStatus"] }) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.approvals.list(params),
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/approvals", { params: { query: params }, signal })),
    placeholderData: keepPreviousData,
  });
}

export function useApproval(approvalId: string | null | undefined) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.approvals.detail(approvalId ?? ""),
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/approvals/{approvalId}", {
          params: { path: { approvalId: approvalId ?? "" } },
          signal,
        }),
      ),
    enabled: Boolean(approvalId),
  });
}

export interface DecideApprovalVariables {
  approvalId: string;
  decision: "APPROVE" | "REJECT";
  reason?: string;
  idempotencyKey: string;
}

export function useDecideApproval() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ approvalId, decision, reason, idempotencyKey }: DecideApprovalVariables) =>
      unwrap(
        await api.POST("/api/v1/approvals/{approvalId}/decisions", {
          params: { path: { approvalId }, header: { "Idempotency-Key": idempotencyKey } },
          body: { decision, ...(reason ? { reason } : {}) },
        }),
      ),
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.approvals.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.orders.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.inventory.all }),
      ]);
    },
  });
}
