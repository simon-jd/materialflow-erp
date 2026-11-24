import { Link, useRouteError } from "react-router";
import { EmptyState } from "./EmptyState";

export function NotFoundPage() {
  return (
    <EmptyState title="Page not found">
      <Link to="/orders" className="underline">
        Go to orders
      </Link>
    </EmptyState>
  );
}

export function RouteError() {
  const error = useRouteError();
  console.error(error);
  return (
    <main className="mx-auto max-w-xl px-6 py-16">
      <EmptyState title="This page failed to render">
        Reload the page. If it keeps happening, report what you were doing.
      </EmptyState>
    </main>
  );
}
