import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "./ui/button";

interface PaginationProps {
  page: number;
  totalPages: number;
  totalItems: number;
  onPageChange: (page: number) => void;
  disabled?: boolean;
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPageChange,
  disabled = false,
}: PaginationProps) {
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-4 text-sm">
      <p className="text-muted-foreground">
        {totalItems} {totalItems === 1 ? "result" : "results"}
        {totalPages > 1 && ` · page ${page + 1} of ${totalPages}`}
      </p>
      {totalPages > 1 && (
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={disabled || page === 0}
            onClick={() => {
              onPageChange(page - 1);
            }}
          >
            <ChevronLeft aria-hidden />
            Previous
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={disabled || page + 1 >= totalPages}
            onClick={() => {
              onPageChange(page + 1);
            }}
          >
            Next
            <ChevronRight aria-hidden />
          </Button>
        </div>
      )}
    </nav>
  );
}
