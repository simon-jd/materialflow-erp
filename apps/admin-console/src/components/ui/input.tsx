import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

export const fieldClassName =
  "flex h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return <input className={cn(fieldClassName, className)} {...props} />;
}

export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return <textarea className={cn(fieldClassName, "min-h-20 py-2", className)} {...props} />;
}

/** Native select: accessible by default and needs no runtime style injection (strict CSP). */
export function NativeSelect({ className, ...props }: ComponentProps<"select">) {
  return <select className={cn(fieldClassName, "pr-8", className)} {...props} />;
}
