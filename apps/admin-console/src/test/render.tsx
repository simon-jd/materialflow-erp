import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter } from "react-router";
import { RouterProvider } from "react-router/dom";
import { vi } from "vitest";
import { ApiClientContext } from "@/api/api-client-context";
import { createApiClient } from "@/api/client";
import { Role, type Session } from "@/auth/session";
import { SessionContext } from "@/auth/session-context";
import { routes } from "@/routes";
import { API, ORGANIZATION_ID, SALES_USER_ID } from "./fixtures";

export const ALL_ROLES = [Role.SalesOrderWrite, Role.ApprovalDecide];

export function testSession(roles: readonly string[] = ALL_ROLES): Session {
  return {
    userId: SALES_USER_ID,
    organizationId: ORGANIZATION_ID,
    displayName: "Synthetic Tester",
    roles: new Set(roles),
    signIn: vi.fn(),
    signOut: vi.fn(),
  };
}

/** Renders the real route tree against MSW, with a synthetic session instead of an OIDC login. */
export function renderApp(path: string, { roles = ALL_ROLES }: { roles?: readonly string[] } = {}) {
  const session = testSession(roles);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retryDelay: 0 } },
  });
  const api = createApiClient({ baseUrl: API, getAccessToken: () => Promise.resolve("test-access-token") });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const user = userEvent.setup();
  render(
    <SessionContext value={session}>
      <ApiClientContext value={api}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ApiClientContext>
    </SessionContext>,
  );
  return { router, session, user };
}
