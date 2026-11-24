import type { Schemas } from "./client";

export interface PageParams {
  page: number;
  size: number;
}

export const queryKeys = {
  orders: {
    all: ["orders"] as const,
    list: (params: PageParams & { status?: Schemas["OrderStatus"] }) => ["orders", "list", params] as const,
    detail: (orderId: string) => ["orders", "detail", orderId] as const,
  },
  approvals: {
    all: ["approvals"] as const,
    list: (params: PageParams & { status?: Schemas["ApprovalStatus"] }) =>
      ["approvals", "list", params] as const,
    detail: (approvalId: string) => ["approvals", "detail", approvalId] as const,
  },
  inventory: {
    all: ["inventory"] as const,
    availability: (params: PageParams) => ["inventory", "availability", params] as const,
  },
  referenceData: {
    customers: (search: string) => ["reference-data", "customers", search] as const,
    materials: ["reference-data", "materials"] as const,
    warehouses: ["reference-data", "warehouses"] as const,
  },
};
