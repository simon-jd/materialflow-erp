import type { Schemas } from "@/api/client";

export const API = "http://api.test";
export const ORGANIZATION_ID = "11111111-1111-1111-1111-111111111111";
export const SALES_USER_ID = "66666666-6666-6666-6666-666666666601";
export const APPROVER_ID = "66666666-6666-6666-6666-666666666602";
export const CUSTOMER_ID = "33333333-3333-3333-3333-333333333333";
export const WAREHOUSE_ID = "22222222-2222-2222-2222-222222222222";
export const CEMENT_ID = "55555555-5555-5555-5555-555555555555";
export const PLYWOOD_ID = "55555555-5555-5555-5555-555555555556";
export const CEMENT_LOCATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
export const PLYWOOD_LOCATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
export const ORDER_ID = "0b6f7a8e-2c1d-4e5f-8a9b-0c1d2e3f4a5b";
export const APPROVAL_ID = "1c7a8b9f-3d2e-4f60-9bac-1d2e3f4a5b6c";

export function page<T>(items: T[], overrides: Partial<Schemas["PageMetadata"]> = {}) {
  return {
    items,
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    ...overrides,
  };
}

export const customer: Schemas["Customer"] = {
  id: CUSTOMER_ID,
  code: "CUST-001",
  name: "Cedar Ridge Contractors",
  regionCode: "WEST",
};

export const materials: Schemas["Material"][] = [
  { id: CEMENT_ID, sku: "CEM-42-5", name: "Portland Cement", specification: "42.5 MPa", baseUnit: "PIECE" },
  { id: PLYWOOD_ID, sku: "PLY-18-2440", name: "Structural Plywood", specification: null, baseUnit: "PIECE" },
];

export const warehouse: Schemas["Warehouse"] = {
  id: WAREHOUSE_ID,
  code: "WH-WEST-01",
  name: "Synthetic West Distribution Center",
  regionCode: "WEST",
};

export const stock: Schemas["InventoryAvailability"][] = [
  {
    warehouseId: WAREHOUSE_ID,
    warehouseCode: "WH-WEST-01",
    warehouseName: "Synthetic West Distribution Center",
    materialId: CEMENT_ID,
    sku: "CEM-42-5",
    materialName: "Portland Cement",
    baseUnit: "PIECE",
    locationId: CEMENT_LOCATION,
    onHand: 1000,
    reserved: 40,
    available: 960,
    inTransit: 0,
    updatedAt: "2026-11-23T10:00:00Z",
  },
  {
    warehouseId: WAREHOUSE_ID,
    warehouseCode: "WH-WEST-01",
    warehouseName: "Synthetic West Distribution Center",
    materialId: PLYWOOD_ID,
    sku: "PLY-18-2440",
    materialName: "Structural Plywood",
    baseUnit: "PIECE",
    locationId: PLYWOOD_LOCATION,
    onHand: 500,
    reserved: 500,
    available: 0,
    inTransit: 25,
    updatedAt: "2026-11-23T10:00:00Z",
  },
];

export function orderSummary(overrides: Partial<Schemas["OrderSummary"]> = {}): Schemas["OrderSummary"] {
  return {
    id: ORDER_ID,
    orderNumber: "SO-0B6F7A8E2C1D4E5F8A9B",
    customerId: CUSTOMER_ID,
    customerName: "Cedar Ridge Contractors",
    status: "RESERVED",
    totalAmount: 1048.13,
    currency: "USD",
    approvalInstanceId: null,
    createdAt: "2026-11-23T10:15:00Z",
    version: 2,
    ...overrides,
  };
}

export function order(overrides: Partial<Schemas["Order"]> = {}): Schemas["Order"] {
  return {
    id: ORDER_ID,
    orderNumber: "SO-0B6F7A8E2C1D4E5F8A9B",
    customerId: CUSTOMER_ID,
    status: "PARTIALLY_ALLOCATED",
    totalAmount: 1520.25,
    approvalInstanceId: null,
    version: 2,
    lines: [
      {
        id: "2d8b9c0a-4e3f-4a71-8cbd-2e3f4a5b6c7d",
        lineNumber: 1,
        materialId: CEMENT_ID,
        warehouseId: WAREHOUSE_ID,
        locationId: CEMENT_LOCATION,
        orderedQuantity: 100,
        allocatedQuantity: 100,
        fulfilledQuantity: 0,
        invoicedQuantity: 0,
        unitPrice: 10.75,
        discountRate: 0.025,
        netAmount: 1048.13,
        priceSource: "CONTRACT",
        priceRuleVersion: 3,
      },
      {
        id: "3e9cad1b-5f40-4b82-9dce-3f4a5b6c7d8e",
        lineNumber: 2,
        materialId: PLYWOOD_ID,
        warehouseId: WAREHOUSE_ID,
        locationId: PLYWOOD_LOCATION,
        orderedQuantity: 10,
        allocatedQuantity: 4,
        fulfilledQuantity: 0,
        invoicedQuantity: 0,
        unitPrice: 47.25,
        discountRate: 0,
        netAmount: 472.5,
        priceSource: "STANDARD",
        priceRuleVersion: 1,
      },
    ],
    ...overrides,
  };
}

export function approvalSummary(
  overrides: Partial<Schemas["ApprovalSummary"]> = {},
): Schemas["ApprovalSummary"] {
  return {
    approvalId: APPROVAL_ID,
    orderId: ORDER_ID,
    orderNumber: "SO-0B6F7A8E2C1D4E5F8A9B",
    customerName: "Cedar Ridge Contractors",
    totalAmount: 31443.75,
    applicantId: SALES_USER_ID,
    status: "PENDING",
    mode: "ANY",
    requiredApprovals: 1,
    approvedCount: 0,
    createdAt: "2026-11-23T11:00:00Z",
    version: 0,
    decidableByActor: true,
    ...overrides,
  };
}

export function apiError(errorCode: string, message: string, fieldErrors: Record<string, string> = {}) {
  return {
    errorCode,
    message,
    traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
    timestamp: "2026-11-23T11:00:00Z",
    fieldErrors,
  };
}
