import { useQuery } from "@tanstack/react-query";
import { useApiClient } from "./api-client-context";
import { unwrap, type Schemas } from "./client";
import { queryKeys } from "./query-keys";

/** Reference data changes rarely; keep it cached across screens for a few minutes. */
const REFERENCE_STALE_MS = 5 * 60_000;
const LOOKUP_PAGE_SIZE = 100;

export function useCustomers(search: string) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.referenceData.customers(search),
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET("/api/v1/customers", {
          params: { query: { size: LOOKUP_PAGE_SIZE, ...(search ? { q: search } : {}) } },
          signal,
        }),
      ),
    staleTime: REFERENCE_STALE_MS,
  });
}

function byId<T extends { id: string }>(items: readonly T[]): ReadonlyMap<string, T> {
  return new Map(items.map((item) => [item.id, item]));
}

/** id -> material / warehouse, used to show SKUs and warehouse codes for order lines. */
export function useReferenceLookups() {
  const api = useApiClient();
  const materials = useQuery({
    queryKey: queryKeys.referenceData.materials,
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/materials", { params: { query: { size: LOOKUP_PAGE_SIZE } }, signal })),
    staleTime: REFERENCE_STALE_MS,
    select: (page): ReadonlyMap<string, Schemas["Material"]> => byId(page.items),
  });
  const warehouses = useQuery({
    queryKey: queryKeys.referenceData.warehouses,
    queryFn: async ({ signal }) =>
      unwrap(await api.GET("/api/v1/warehouses", { params: { query: { size: LOOKUP_PAGE_SIZE } }, signal })),
    staleTime: REFERENCE_STALE_MS,
    select: (page): ReadonlyMap<string, Schemas["Warehouse"]> => byId(page.items),
  });
  return { materials: materials.data, warehouses: warehouses.data };
}
