import type { ReactNode } from "react";

/** Full-page message used before the app shell exists (sign-in, configuration errors). */
export function StatusScreen({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <main className="grid min-h-dvh place-items-center px-6">
      <div role="status" className="flex max-w-md flex-col items-center gap-3 text-center">
        <h1 className="text-lg font-semibold">{title}</h1>
        {children && (
          <div className="flex flex-col items-center gap-3 text-sm text-muted-foreground">{children}</div>
        )}
      </div>
    </main>
  );
}
