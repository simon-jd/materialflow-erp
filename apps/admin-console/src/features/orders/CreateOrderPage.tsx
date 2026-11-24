import { zodResolver } from "@hookform/resolvers/zod";
import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useFieldArray, useForm, useWatch, type FieldPath } from "react-hook-form";
import { Link, useNavigate } from "react-router";
import type { Schemas } from "@/api/client";
import { toProblem } from "@/api/problem";
import { useCustomers } from "@/api/reference-data";
import { hasRole, Role } from "@/auth/session";
import { useSession } from "@/auth/session-context";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { ProblemAlert } from "@/components/ProblemAlert";
import { Button } from "@/components/ui/button";
import { buttonVariants } from "@/components/ui/button-variants";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, NativeSelect } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { formatQuantity } from "@/lib/format";
import { useDebouncedValue } from "@/lib/use-debounced-value";
import { useSubmissionKey } from "@/lib/use-submission-key";
import { useInventoryAvailability } from "../inventory/inventory-queries";
import {
  emptyOrderForm,
  MAX_ORDER_LINES,
  orderFormSchema,
  stockPositionKey,
  toCreateOrderRequest,
  toFormFieldPath,
  type OrderFormValues,
} from "./order-form";
import { useCreateOrder } from "./order-queries";

const STOCK_PAGE = { page: 0, size: 100 } as const;

function FieldError({ id, message }: { id: string; message: string | undefined }) {
  return message ? (
    <p id={id} className="text-sm text-destructive">
      {message}
    </p>
  ) : null;
}

export function CreateOrderPage() {
  const session = useSession();
  if (!hasRole(session, Role.SalesOrderWrite)) {
    return (
      <div className="flex flex-col gap-6">
        <PageHeader title="New sales order" />
        <EmptyState title="You cannot create orders">
          Your account does not have the sales-order role. You can still browse orders and inventory.
        </EmptyState>
      </div>
    );
  }
  return <CreateOrderForm />;
}

function CreateOrderForm() {
  const navigate = useNavigate();
  const createOrder = useCreateOrder();
  const submission = useSubmissionKey();
  const [customerSearch, setCustomerSearch] = useState("");
  const customers = useCustomers(useDebouncedValue(customerSearch.trim(), 300));
  const stock = useInventoryAvailability(STOCK_PAGE);

  const form = useForm<OrderFormValues>({
    resolver: zodResolver(orderFormSchema),
    defaultValues: emptyOrderForm,
    mode: "onTouched",
  });
  const { errors } = form.formState;
  const lines = useFieldArray({ control: form.control, name: "lines" });
  const watchedLines = useWatch({ control: form.control, name: "lines" });
  const allowPartial = useWatch({ control: form.control, name: "allowPartialFulfillment" });
  const positions = new Map<string, Schemas["InventoryAvailability"]>(
    (stock.data?.items ?? []).map((position) => [stockPositionKey(position), position]),
  );

  const onSubmit = form.handleSubmit((values) => {
    const body = toCreateOrderRequest(values);
    createOrder.mutate(
      { body, idempotencyKey: submission.keyFor(body) },
      {
        onSuccess: (order) => {
          submission.reset();
          void navigate(`/orders/${order.id}`, { state: { created: true } });
        },
        onError: (error) => {
          for (const [field, message] of Object.entries(toProblem(error).fieldErrors)) {
            const path = toFormFieldPath(field);
            if (path === "customerId" || /^lines\.\d+\.quantity$/.test(path)) {
              form.setError(path as FieldPath<OrderFormValues>, { message });
            }
          }
        },
      },
    );
  });

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="New sales order"
        description="Pricing, credit, approval rules and stock allocation are applied by the server on submit."
      />
      <form noValidate onSubmit={(event) => void onSubmit(event)} className="flex flex-col gap-6">
        {createOrder.isError && <ProblemAlert error={createOrder.error} />}
        <Card>
          <CardHeader>
            <CardTitle>Customer</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <Label htmlFor="customer-search">Find customer</Label>
              <Input
                id="customer-search"
                type="search"
                placeholder="Code or name"
                value={customerSearch}
                onChange={(event) => {
                  setCustomerSearch(event.target.value);
                }}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="customerId">Customer</Label>
              <NativeSelect
                id="customerId"
                aria-invalid={errors.customerId ? true : undefined}
                aria-describedby={errors.customerId ? "customerId-error" : undefined}
                disabled={customers.isPending}
                {...form.register("customerId")}
              >
                <option value="">{customers.isPending ? "Loading customers..." : "Select a customer"}</option>
                {customers.data?.items.map((customer) => (
                  <option key={customer.id} value={customer.id}>
                    {customer.code} · {customer.name}
                  </option>
                ))}
              </NativeSelect>
              <FieldError id="customerId-error" message={errors.customerId?.message} />
            </div>
            <label className="flex items-center gap-2 text-sm sm:col-span-2">
              <input
                type="checkbox"
                className="size-4 accent-primary"
                {...form.register("allowPartialFulfillment")}
              />
              Allow partial fulfillment (reserve what is available and backorder the rest)
            </label>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Lines</CardTitle>
            <CardDescription>
              Choose a stock position (warehouse, material and location) and a quantity in the material&apos;s
              base unit.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {stock.isError && <ProblemAlert error={stock.error} onRetry={() => void stock.refetch()} />}
            {lines.fields.map((field, index) => {
              const lineErrors = errors.lines?.[index];
              const position = positions.get(watchedLines[index]?.stockPosition ?? "");
              const quantity = Number(watchedLines[index]?.quantity);
              const short = position !== undefined && quantity > position.available;
              return (
                <fieldset
                  key={field.id}
                  className="grid gap-3 rounded-md border p-3 sm:grid-cols-[1fr_12rem_auto]"
                >
                  <legend className="px-1 text-sm font-medium">Line {index + 1}</legend>
                  <div className="flex flex-col gap-2">
                    <Label htmlFor={`lines.${index}.stockPosition`}>Stock position</Label>
                    <NativeSelect
                      id={`lines.${index}.stockPosition`}
                      aria-invalid={lineErrors?.stockPosition ? true : undefined}
                      aria-describedby={
                        lineErrors?.stockPosition ? `lines.${index}.stockPosition-error` : undefined
                      }
                      disabled={stock.isPending}
                      {...form.register(`lines.${index}.stockPosition`)}
                    >
                      <option value="">{stock.isPending ? "Loading stock..." : "Select stock"}</option>
                      {stock.data?.items.map((item) => (
                        <option key={stockPositionKey(item)} value={stockPositionKey(item)}>
                          {item.warehouseCode} · {item.sku} {item.materialName} ·{" "}
                          {formatQuantity(item.available)} available
                        </option>
                      ))}
                    </NativeSelect>
                    <FieldError
                      id={`lines.${index}.stockPosition-error`}
                      message={lineErrors?.stockPosition?.message}
                    />
                  </div>
                  <div className="flex flex-col gap-2">
                    <Label htmlFor={`lines.${index}.quantity`}>Quantity</Label>
                    <Input
                      id={`lines.${index}.quantity`}
                      inputMode="decimal"
                      autoComplete="off"
                      aria-invalid={lineErrors?.quantity ? true : undefined}
                      aria-describedby={lineErrors?.quantity ? `lines.${index}.quantity-error` : undefined}
                      {...form.register(`lines.${index}.quantity`)}
                    />
                    <FieldError
                      id={`lines.${index}.quantity-error`}
                      message={lineErrors?.quantity?.message}
                    />
                    {short && !lineErrors?.quantity && (
                      <p className="text-sm text-warning">
                        Only {formatQuantity(position.available)} available now;{" "}
                        {allowPartial
                          ? "the rest will be backordered."
                          : "the whole order will be backordered."}
                      </p>
                    )}
                  </div>
                  <div className="flex items-end">
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label={`Remove line ${index + 1}`}
                      disabled={lines.fields.length === 1}
                      onClick={() => {
                        lines.remove(index);
                      }}
                    >
                      <Trash2 aria-hidden />
                    </Button>
                  </div>
                </fieldset>
              );
            })}
            <FieldError id="lines-error" message={errors.lines?.root?.message ?? errors.lines?.message} />
            <div>
              <Button
                variant="outline"
                size="sm"
                disabled={lines.fields.length >= MAX_ORDER_LINES}
                onClick={() => {
                  lines.append({ stockPosition: "", quantity: "" });
                }}
              >
                <Plus aria-hidden />
                Add line
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end gap-2">
          <Link to="/orders" className={buttonVariants({ variant: "outline" })}>
            Cancel
          </Link>
          <Button type="submit" disabled={createOrder.isPending}>
            {createOrder.isPending ? "Submitting..." : "Submit order"}
          </Button>
        </div>
      </form>
    </div>
  );
}
