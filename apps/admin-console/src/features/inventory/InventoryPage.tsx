import { useSearchParams } from "react-router";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ProblemAlert } from "@/components/ProblemAlert";
import { TableSkeleton } from "@/components/TableSkeleton";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime, formatQuantity, humanize, shortId } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useInventoryAvailability } from "./inventory-queries";

const PAGE_SIZE = 50;

export function InventoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(0, Number.parseInt(searchParams.get("page") ?? "0", 10) || 0);
  const stock = useInventoryAvailability({ page, size: PAGE_SIZE });

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Inventory availability"
        description="Point-in-time stock in your warehouses. Reservation re-checks availability under a row lock."
      />
      <Card>
        <CardContent className="flex flex-col gap-4">
          {stock.isPending ? (
            <TableSkeleton label="Loading inventory" />
          ) : stock.isError ? (
            <ProblemAlert error={stock.error} onRetry={() => void stock.refetch()} />
          ) : stock.data.items.length === 0 ? (
            <EmptyState title="No stock positions">Your warehouse scope has no inventory records.</EmptyState>
          ) : (
            <>
              <Table aria-label="Inventory availability" aria-busy={stock.isPlaceholderData}>
                <TableHeader>
                  <TableRow>
                    <TableHead>Warehouse</TableHead>
                    <TableHead>SKU</TableHead>
                    <TableHead>Material</TableHead>
                    <TableHead>Location</TableHead>
                    <TableHead className="text-right">On hand</TableHead>
                    <TableHead className="text-right">Reserved</TableHead>
                    <TableHead className="text-right">Available</TableHead>
                    <TableHead className="text-right">In transit</TableHead>
                    <TableHead>Updated</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {stock.data.items.map((item) => (
                    <TableRow key={`${item.warehouseId}-${item.materialId}-${item.locationId}`}>
                      <TableCell className="font-mono" title={item.warehouseName}>
                        {item.warehouseCode}
                      </TableCell>
                      <TableCell className="font-mono">{item.sku}</TableCell>
                      <TableCell>
                        {item.materialName}{" "}
                        <span className="text-muted-foreground">({humanize(item.baseUnit)})</span>
                      </TableCell>
                      <TableCell className="font-mono text-muted-foreground">
                        {shortId(item.locationId)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatQuantity(item.onHand)}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatQuantity(item.reserved)}
                      </TableCell>
                      <TableCell
                        className={cn(
                          "text-right font-medium tabular-nums",
                          item.available === 0 && "text-destructive",
                        )}
                      >
                        {formatQuantity(item.available)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatQuantity(item.inTransit)}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {formatDateTime(item.updatedAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination
                page={stock.data.page}
                totalPages={stock.data.totalPages}
                totalItems={stock.data.totalItems}
                disabled={stock.isPlaceholderData}
                onPageChange={(next) => {
                  setSearchParams(next > 0 ? { page: String(next) } : {});
                }}
              />
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
