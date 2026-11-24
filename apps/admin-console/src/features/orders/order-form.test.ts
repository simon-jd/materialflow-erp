import { describe, expect, it } from "vitest";
import { CEMENT_ID, CEMENT_LOCATION, CUSTOMER_ID, WAREHOUSE_ID } from "@/test/fixtures";
import { orderFormSchema, stockPositionKey, toCreateOrderRequest, toFormFieldPath } from "./order-form";

const cement = stockPositionKey({
  warehouseId: WAREHOUSE_ID,
  materialId: CEMENT_ID,
  locationId: CEMENT_LOCATION,
});

function errorsFor(values: unknown): string[] {
  const result = orderFormSchema.safeParse(values);
  return result.success
    ? []
    : result.error.issues.map((issue) => `${issue.path.join(".")}: ${issue.message}`);
}

describe("orderFormSchema", () => {
  it("accepts a valid order", () => {
    expect(
      errorsFor({
        customerId: CUSTOMER_ID,
        allowPartialFulfillment: true,
        lines: [{ stockPosition: cement, quantity: "2.5" }],
      }),
    ).toEqual([]);
  });

  it.each([
    ["", "Enter a quantity"],
    ["0", "Quantity must be greater than zero"],
    ["-1", "Use a number with at most 6 decimal places"],
    ["1.1234567", "Use a number with at most 6 decimal places"],
    ["abc", "Use a number with at most 6 decimal places"],
  ])("rejects quantity %j", (quantity, message) => {
    expect(
      errorsFor({
        customerId: CUSTOMER_ID,
        allowPartialFulfillment: true,
        lines: [{ stockPosition: cement, quantity }],
      }),
    ).toContain(`lines.0.quantity: ${message}`);
  });

  it("requires a customer and at least one line", () => {
    expect(errorsFor({ customerId: "", allowPartialFulfillment: false, lines: [] })).toEqual([
      "customerId: Choose a customer",
      "lines: Add at least one line",
    ]);
  });

  it("rejects the same stock position on two lines", () => {
    expect(
      errorsFor({
        customerId: CUSTOMER_ID,
        allowPartialFulfillment: true,
        lines: [
          { stockPosition: cement, quantity: "1" },
          { stockPosition: cement, quantity: "2" },
        ],
      }),
    ).toEqual([
      "lines.1.stockPosition: This stock position is already on another line; combine the quantities",
    ]);
  });
});

describe("toCreateOrderRequest", () => {
  it("expands stock positions and converts quantities to numbers", () => {
    expect(
      toCreateOrderRequest({
        customerId: CUSTOMER_ID,
        allowPartialFulfillment: false,
        lines: [{ stockPosition: cement, quantity: "0.001" }],
      }),
    ).toEqual({
      customerId: CUSTOMER_ID,
      allowPartialFulfillment: false,
      lines: [
        { warehouseId: WAREHOUSE_ID, materialId: CEMENT_ID, locationId: CEMENT_LOCATION, quantity: 0.001 },
      ],
    });
  });

  it("maps server field paths onto form fields", () => {
    expect(toFormFieldPath("lines[3].quantity")).toBe("lines.3.quantity");
    expect(toFormFieldPath("customerId")).toBe("customerId");
  });
});
