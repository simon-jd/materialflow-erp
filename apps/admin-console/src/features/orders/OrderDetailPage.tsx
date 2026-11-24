import { ArrowLeft, CheckCircle2 } from "lucide-react";
import type { ReactNode } from "react";
import { Link, useLocation, useParams } from "react-router";
import type { Schemas } from "@/api/client";
import { useReferenceLookups } from "@/api/reference-data";
import { hasRole, Role } from "@/auth/session";
import { useSession } from "@/auth/session-context";
import { PageHeader } from "@/components/PageHeader";
import { ProblemAlert } from "@/components/ProblemAlert";
import { ApprovalStatusBadge, OrderStatusBadge } from "@/components/StatusBadge";
import { TableSkeleton } from "@/components/TableSkeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button-variants";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime, formatMoney, formatQuantity, humanize, shortId } from "@/lib/format";
import { ApprovalActions } from "../approvals/ApprovalActions";
import { useApproval } from "../approvals/approval-queries";
import { useOrder } from "./order-queries";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const NOT_ALLOCATING: ReadonlySet<Schemas["OrderStatus"]> = new Set(["REJECTED", "CANCELLED", "EXPIRED"]);

function allocationState(
  order: Schemas["Order"],
  line: Schemas["OrderLine"],
): { label: string; variant: BadgeVariant } {
  if (order.status === "PENDING_APPROVAL") {
    return { label: "Awaiting approval", variant: "secondary" };
  }
  if (NOT_ALLOCATING.has(order.status)) {
    return { label: "Not allocated", variant: "secondary" };
  }
  if (line.allocatedQuantity >= line.orderedQuantity) {
    return { label: "Allocated", variant: "success" };
  }
  return line.allocatedQuantity > 0
    ? { label: "Partially backordered", variant: "warning" }
    : { label: "Backordered", variant: "warning" };
}

export function OrderDetailPage() {
  const { orderId = "" } = useParams();
  if (!UUID.test(orderId)) {
    return (
      <OrderDetailFrame title="Order not found">
        <Alert variant="destructive">
          <AlertTitle>Not found</AlertTitle>
          <AlertDescription>“{orderId}” is not a valid order reference.</AlertDescription>
        </Alert>
      </OrderDetailFrame>
    );
  }
  return <OrderDetail orderId={orderId} />;
}

function OrderDetailFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link to="/orders" className={buttonVariants({ variant: "ghost", size: "sm" })}>
          <ArrowLeft aria-hidden />
          All orders
        </Link>
      </div>
      <PageHeader title={title} />
      {children}
    </div>
  );
}

function OrderDetail({ orderId }: { orderId: string }) {
  const location = useLocation();
  const justCreated = (location.state as { created?: boolean } | null)?.created === true;
  const order = useOrder(orderId);
  const { materials, warehouses } = useReferenceLookups();

  if (order.isPending) {
    return (
      <OrderDetailFrame title="Sales order">
        <TableSkeleton rows={3} label="Loading order" />
      </OrderDetailFrame>
    );
  }
  if (order.isError) {
    return (
      <OrderDetailFrame title="Sales order">
        <ProblemAlert error={order.error} onRetry={() => void order.refetch()} />
      </OrderDetailFrame>
    );
  }

  const data = order.data;
  const ordered = data.lines.reduce((sum, line) => sum + line.orderedQuantity, 0);
  const allocated = data.lines.reduce((sum, line) => sum + line.allocatedQuantity, 0);
  return (
    <OrderDetailFrame title={`Order ${data.orderNumber}`}>
      {justCreated && (
        <Alert>
          <CheckCircle2 aria-hidden className="text-success" />
          <AlertTitle>Order submitted</AlertTitle>
          <AlertDescription>
            {data.status === "PENDING_APPROVAL"
              ? "It needs approval before stock is reserved."
              : "Credit and stock were reserved in the same transaction as the order."}
          </AlertDescription>
        </Alert>
      )}
      <Card>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-4">
            <div>
              <dt className="text-sm text-muted-foreground">Status</dt>
              <dd className="mt-1">
                <OrderStatusBadge status={data.status} />
              </dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">Total</dt>
              <dd className="mt-1 font-medium tabular-nums">{formatMoney(data.totalAmount)}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">Allocated</dt>
              <dd className="mt-1 tabular-nums">
                {formatQuantity(allocated)} of {formatQuantity(ordered)}
              </dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">Version</dt>
              <dd className="mt-1 tabular-nums">{data.version}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Lines</CardTitle>
          <CardDescription>Backordered quantity is ordered minus allocated.</CardDescription>
        </CardHeader>
        <CardContent>
          <Table aria-label="Order lines">
            <TableHeader>
              <TableRow>
                <TableHead>#</TableHead>
                <TableHead>Material</TableHead>
                <TableHead>Warehouse</TableHead>
                <TableHead className="text-right">Ordered</TableHead>
                <TableHead className="text-right">Allocated</TableHead>
                <TableHead className="text-right">Backordered</TableHead>
                <TableHead className="text-right">Unit price</TableHead>
                <TableHead className="text-right">Net</TableHead>
                <TableHead>Allocation</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.lines.map((line) => {
                const material = materials?.get(line.materialId);
                const state = allocationState(data, line);
                return (
                  <TableRow key={line.id}>
                    <TableCell>{line.lineNumber}</TableCell>
                    <TableCell>
                      {material ? (
                        <>
                          <span className="font-mono">{material.sku}</span> {material.name}
                        </>
                      ) : (
                        <span className="font-mono">{shortId(line.materialId)}</span>
                      )}
                    </TableCell>
                    <TableCell className="font-mono">
                      {warehouses?.get(line.warehouseId)?.code ?? shortId(line.warehouseId)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(line.orderedQuantity)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(line.allocatedQuantity)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(Math.max(line.orderedQuantity - line.allocatedQuantity, 0))}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{line.unitPrice.toFixed(4)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatMoney(line.netAmount)}</TableCell>
                    <TableCell>
                      <Badge variant={state.variant}>{state.label}</Badge>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {data.approvalInstanceId && (
        <ApprovalCard approvalId={data.approvalInstanceId} orderNumber={data.orderNumber} />
      )}
    </OrderDetailFrame>
  );
}

function ApprovalCard({ approvalId, orderNumber }: { approvalId: string; orderNumber: string }) {
  const session = useSession();
  const approval = useApproval(approvalId);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Approval</CardTitle>
        <CardDescription>Maker-checker: the submitter cannot decide their own order.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {approval.isPending ? (
          <TableSkeleton rows={2} label="Loading approval" />
        ) : approval.isError ? (
          <ProblemAlert error={approval.error} onRetry={() => void approval.refetch()} />
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-4 text-sm">
              <ApprovalStatusBadge status={approval.data.approval.status} />
              <span>
                {approval.data.approval.approvedCount} of {approval.data.approval.requiredApprovals} approvals
                ({humanize(approval.data.approval.mode)})
              </span>
            </div>
            {approval.data.decisions.length > 0 && (
              <ul aria-label="Decisions" className="flex flex-col gap-1 text-sm">
                {approval.data.decisions.map((decision) => (
                  <li key={`${decision.approverId}-${decision.decidedAt}`}>
                    {decision.decision === "APPROVE" ? "Approved" : "Rejected"} by{" "}
                    {decision.approverId === session.userId ? (
                      "you"
                    ) : (
                      <span className="font-mono">{shortId(decision.approverId)}</span>
                    )}{" "}
                    on {formatDateTime(decision.decidedAt)}
                    {decision.reason && <span className="text-muted-foreground"> — {decision.reason}</span>}
                  </li>
                ))}
              </ul>
            )}
            {approval.data.approval.decidableByActor && hasRole(session, Role.ApprovalDecide) && (
              <ApprovalActions approvalId={approvalId} orderNumber={orderNumber} />
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
