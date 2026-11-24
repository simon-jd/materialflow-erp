import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useApiClient } from "@/api/api-client-context";
import { unwrap } from "@/api/client";
import { queryKeys, type PageParams } from "@/api/query-keys";

export function useInventoryAvailability(params: PageParams) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.inventory.availability(params),
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/inventory/availability", { params: { query: params }, signal })),
    placeholderData: keepPreviousData,
  });
}
