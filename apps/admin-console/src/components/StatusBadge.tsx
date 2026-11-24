import type { Schemas } from "@/api/client";
import { humanize } from "@/lib/format";
import { Badge, type BadgeVariant } from "./ui/badge";

const ORDER_VARIANTS: Partial<Record<Schemas["OrderStatus"], BadgeVariant>> = {
  RESERVED: "success",
  FULFILLED: "success",
  INVOICED: "success",
  SETTLED: "success",
  PENDING_APPROVAL: "warning",
  PARTIALLY_ALLOCATED: "warning",
  BACKORDERED: "warning",
  REJECTED: "destructive",
  CANCELLED: "destructive",
  EXPIRED: "destructive",
  RECONCILIATION_EXCEPTION: "destructive",
};

const APPROVAL_VARIANTS: Record<Schemas["ApprovalStatus"], BadgeVariant> = {
  PENDING: "warning",
  APPROVED: "success",
  REJECTED: "destructive",
  WITHDRAWN: "secondary",
  EXPIRED: "secondary",
};

export function OrderStatusBadge({ status }: { status: Schemas["OrderStatus"] }) {
  return <Badge variant={ORDER_VARIANTS[status] ?? "secondary"}>{humanize(status)}</Badge>;
}

export function ApprovalStatusBadge({ status }: { status: Schemas["ApprovalStatus"] }) {
  return <Badge variant={APPROVAL_VARIANTS[status]}>{humanize(status)}</Badge>;
}
