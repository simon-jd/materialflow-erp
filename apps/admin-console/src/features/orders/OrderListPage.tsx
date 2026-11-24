import { Plus } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import type { Schemas } from "@/api/client";
import { orderStatusValues } from "@/api/generated/schema";
import { hasRole, Role } from "@/auth/session";
import { useSession } from "@/auth/session-context";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ProblemAlert } from "@/components/ProblemAlert";
import { OrderStatusBadge } from "@/components/StatusBadge";
import { TableSkeleton } from "@/components/TableSkeleton";
import { buttonVariants } from "@/components/ui/button-variants";
import { Card, CardContent } from "@/components/ui/card";
import { NativeSelect } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime, formatMoney, humanize } from "@/lib/format";
import { useOrders } from "./order-queries";

const PAGE_SIZE = 20;

function parseStatus(value: string | null): Schemas["OrderStatus"] | undefined {
  return orderStatusValues.find((status) => status === value);
}

function parsePage(value: string | null): number {
  const page = Number(value);
  return Number.isInteger(page) && page > 0 ? page : 0;
}

export function OrderListPage() {
  const session = useSession();
  const [searchParams, setSearchParams] = useSearchParams();
  const status = parseStatus(searchParams.get("status"));
  const page = parsePage(searchParams.get("page"));
  const orders = useOrders({ page, size: PAGE_SIZE, ...(status ? { status } : {}) });

  const update = (next: { status?: string; page?: number }) => {
    setSearchParams((current) => {
      const params = new URLSearchParams(current);
      if (next.status !== undefined) {
        if (next.status) params.set("status", next.status);
        else params.delete("status");
        params.delete("page");
      }
      if (next.page !== undefined) {
        if (next.page > 0) params.set("page", String(next.page));
        else params.delete("page");
      }
      return params;
    });
  };

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Sales orders"
        description="Newest first. Totals and allocation come straight from the transactional core."
        actions={
          hasRole(session, Role.SalesOrderWrite) && (
            <Link to="/orders/new" className={buttonVariants()}>
              <Plus aria-hidden />
              New order
            </Link>
          )
        }
      />
      <Card>
        <CardContent className="flex flex-col gap-4">
          <div className="flex items-end gap-3">
            <div className="flex flex-col gap-2">
              <Label htmlFor="status-filter">Status</Label>
              <NativeSelect
                id="status-filter"
                className="w-56"
                value={status ?? ""}
                onChange={(event) => {
                  update({ status: event.target.value });
                }}
              >
                <option value="">All statuses</option>
                {orderStatusValues.map((value) => (
                  <option key={value} value={value}>
                    {humanize(value)}
                  </option>
                ))}
              </NativeSelect>
            </div>
          </div>
          {orders.isPending ? (
            <TableSkeleton label="Loading orders" />
          ) : orders.isError ? (
            <ProblemAlert error={orders.error} onRetry={() => void orders.refetch()} />
          ) : orders.data.items.length === 0 ? (
            <EmptyState title={status ? `No ${humanize(status).toLowerCase()} orders` : "No orders yet"}>
              {hasRole(session, Role.SalesOrderWrite)
                ? "Orders you create appear here."
                : "Orders created in your organization appear here."}
            </EmptyState>
          ) : (
            <>
              <Table aria-label="Sales orders" aria-busy={orders.isPlaceholderData}>
                <TableHeader>
                  <TableRow>
                    <TableHead>Order</TableHead>
                    <TableHead>Customer</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="text-right">Total</TableHead>
                    <TableHead>Created</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {orders.data.items.map((order) => (
                    <TableRow key={order.id}>
                      <TableCell>
                        <Link to={`/orders/${order.id}`} className="font-mono font-medium hover:underline">
                          {order.orderNumber}
                        </Link>
                      </TableCell>
                      <TableCell>{order.customerName}</TableCell>
                      <TableCell>
                        <OrderStatusBadge status={order.status} />
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatMoney(order.totalAmount, order.currency)}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {formatDateTime(order.createdAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination
                page={orders.data.page}
                totalPages={orders.data.totalPages}
                totalItems={orders.data.totalItems}
                disabled={orders.isPlaceholderData}
                onPageChange={(next) => {
                  update({ page: next });
                }}
              />
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
