import { Link, useSearchParams } from "react-router";
import type { Schemas } from "@/api/client";
import { approvalStatusValues } from "@/api/generated/schema";
import { hasRole, Role } from "@/auth/session";
import { useSession } from "@/auth/session-context";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ProblemAlert } from "@/components/ProblemAlert";
import { ApprovalStatusBadge } from "@/components/StatusBadge";
import { TableSkeleton } from "@/components/TableSkeleton";
import { Card, CardContent } from "@/components/ui/card";
import { NativeSelect } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime, formatMoney, humanize } from "@/lib/format";
import { ApprovalActions } from "./ApprovalActions";
import { useApprovals } from "./approval-queries";

const PAGE_SIZE = 20;
const ALL = "ALL";

function parseStatus(value: string | null): Schemas["ApprovalStatus"] | undefined {
  if (value === null) return "PENDING";
  return approvalStatusValues.find((status) => status === value);
}

export function ApprovalQueuePage() {
  const session = useSession();
  const canDecide = hasRole(session, Role.ApprovalDecide);
  const [searchParams, setSearchParams] = useSearchParams();
  const status = parseStatus(searchParams.get("status"));
  const page = Math.max(0, Number.parseInt(searchParams.get("page") ?? "0", 10) || 0);
  const approvals = useApprovals({ page, size: PAGE_SIZE, ...(status ? { status } : {}) });

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Approval queue"
        description={
          canDecide
            ? "Orders above the amount or discount threshold, or over the customer's credit, wait here for a checker."
            : "You can follow approvals; deciding them requires the approval role."
        }
      />
      <Card>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="approval-status">Status</Label>
            <NativeSelect
              id="approval-status"
              className="w-48"
              value={status ?? ALL}
              onChange={(event) => {
                setSearchParams({ status: event.target.value });
              }}
            >
              <option value={ALL}>All statuses</option>
              {approvalStatusValues.map((value) => (
                <option key={value} value={value}>
                  {humanize(value)}
                </option>
              ))}
            </NativeSelect>
          </div>
          {approvals.isPending ? (
            <TableSkeleton label="Loading approvals" />
          ) : approvals.isError ? (
            <ProblemAlert error={approvals.error} onRetry={() => void approvals.refetch()} />
          ) : approvals.data.items.length === 0 ? (
            <EmptyState title={status === "PENDING" ? "Nothing waiting for approval" : "No approvals"} />
          ) : (
            <>
              <Table aria-label="Approvals" aria-busy={approvals.isPlaceholderData}>
                <TableHeader>
                  <TableRow>
                    <TableHead>Order</TableHead>
                    <TableHead>Customer</TableHead>
                    <TableHead className="text-right">Total</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Approvals</TableHead>
                    <TableHead>Submitted</TableHead>
                    {canDecide && <TableHead>Decision</TableHead>}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {approvals.data.items.map((approval) => (
                    <TableRow key={approval.approvalId}>
                      <TableCell>
                        <Link
                          to={`/orders/${approval.orderId}`}
                          className="font-mono font-medium hover:underline"
                        >
                          {approval.orderNumber}
                        </Link>
                      </TableCell>
                      <TableCell>{approval.customerName}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatMoney(approval.totalAmount)}
                      </TableCell>
                      <TableCell>
                        <ApprovalStatusBadge status={approval.status} />
                      </TableCell>
                      <TableCell className="tabular-nums">
                        {approval.approvedCount} / {approval.requiredApprovals}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {formatDateTime(approval.createdAt)}
                      </TableCell>
                      {canDecide && (
                        <TableCell>
                          {approval.decidableByActor ? (
                            <ApprovalActions
                              approvalId={approval.approvalId}
                              orderNumber={approval.orderNumber}
                            />
                          ) : (
                            <span className="text-sm text-muted-foreground">
                              {approval.status === "PENDING" ? "Not assigned to you" : "Closed"}
                            </span>
                          )}
                        </TableCell>
                      )}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination
                page={approvals.data.page}
                totalPages={approvals.data.totalPages}
                totalItems={approvals.data.totalItems}
                disabled={approvals.isPlaceholderData}
                onPageChange={(next) => {
                  setSearchParams({ status: status ?? ALL, page: String(next) });
                }}
              />
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
