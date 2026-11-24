import { z } from "zod";
import type { Schemas } from "@/api/client";

export const MAX_ORDER_LINES = 100;
const DECIMAL = /^\d+(\.\d{1,6})?$/;

/** A stock position is one inventory balance row: warehouse + material + location. */
export function stockPositionKey(position: {
  warehouseId: string;
  materialId: string;
  locationId: string;
}): string {
  return `${position.warehouseId}|${position.materialId}|${position.locationId}`;
}

function parseStockPosition(key: string): { warehouseId: string; materialId: string; locationId: string } {
  const [warehouseId = "", materialId = "", locationId = ""] = key.split("|");
  return { warehouseId, materialId, locationId };
}

const lineSchema = z.object({
  stockPosition: z.string().min(1, "Choose a stock position"),
  quantity: z
    .string()
    .trim()
    .min(1, "Enter a quantity")
    .regex(DECIMAL, "Use a number with at most 6 decimal places")
    .refine((value) => Number(value) > 0, "Quantity must be greater than zero"),
});

export const orderFormSchema = z
  .object({
    customerId: z.string().min(1, "Choose a customer"),
    allowPartialFulfillment: z.boolean(),
    lines: z
      .array(lineSchema)
      .min(1, "Add at least one line")
      .max(MAX_ORDER_LINES, `An order can have at most ${MAX_ORDER_LINES} lines`),
  })
  .superRefine((values, context) => {
    // The core locks each inventory row once per order; two lines on one row would conflict.
    const seen = new Set<string>();
    values.lines.forEach((line, index) => {
      if (line.stockPosition && seen.has(line.stockPosition)) {
        context.addIssue({
          code: "custom",
          path: ["lines", index, "stockPosition"],
          message: "This stock position is already on another line; combine the quantities",
        });
      }
      seen.add(line.stockPosition);
    });
  });

export type OrderFormValues = z.infer<typeof orderFormSchema>;

export const emptyOrderForm: OrderFormValues = {
  customerId: "",
  allowPartialFulfillment: true,
  lines: [{ stockPosition: "", quantity: "" }],
};

export function toCreateOrderRequest(values: OrderFormValues): Schemas["CreateOrderRequest"] {
  return {
    customerId: values.customerId,
    allowPartialFulfillment: values.allowPartialFulfillment,
    lines: values.lines.map((line) => ({
      ...parseStockPosition(line.stockPosition),
      quantity: Number(line.quantity),
    })),
  };
}

/** Maps server field paths such as `lines[0].quantity` onto form field names (`lines.0.quantity`). */
export function toFormFieldPath(serverField: string): string {
  return serverField.replace(/\[(\d+)\]/g, ".$1");
}
