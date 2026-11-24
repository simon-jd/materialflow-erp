import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, apiError, orderSummary, page } from "@/test/fixtures";
import { renderApp } from "@/test/render";
import { server } from "@/test/server";

describe("OrderListPage", () => {
  it("lists orders with status, customer and total", async () => {
    server.use(http.get(`${API}/api/v1/orders`, () => HttpResponse.json(page([orderSummary()]))));
    renderApp("/orders");

    const table = await screen.findByRole("table", { name: "Sales orders" });
    const row = within(table).getByRole("row", { name: /SO-0B6F7A8E2C1D4E5F8A9B/ });
    expect(row).toHaveTextContent("Cedar Ridge Contractors");
    expect(row).toHaveTextContent("Reserved");
    expect(row).toHaveTextContent("USD 1,048.13");
    expect(within(row).getByRole("link", { name: "SO-0B6F7A8E2C1D4E5F8A9B" })).toHaveAttribute(
      "href",
      "/orders/0b6f7a8e-2c1d-4e5f-8a9b-0c1d2e3f4a5b",
    );
    expect(screen.getByText("1 result")).toBeInTheDocument();
  });

  it("filters by status and pages through results via the URL", async () => {
    const requests: URLSearchParams[] = [];
    server.use(
      http.get(`${API}/api/v1/orders`, ({ request }) => {
        const query = new URL(request.url).searchParams;
        requests.push(query);
        const current = Number(query.get("page"));
        return HttpResponse.json(
          page([orderSummary({ orderNumber: `SO-PAGE-${current}` })], {
            page: current,
            totalItems: 25,
            totalPages: 2,
          }),
        );
      }),
    );
    const { user, router } = renderApp("/orders");
    await screen.findByText("SO-PAGE-0");

    await user.selectOptions(screen.getByLabelText("Status"), "BACKORDERED");
    await screen.findByText("SO-PAGE-0");
    expect(router.state.location.search).toBe("?status=BACKORDERED");

    await user.click(screen.getByRole("button", { name: /Next/ }));
    await screen.findByText("SO-PAGE-1");
    expect(requests.at(-1)?.toString()).toBe("page=1&size=20&status=BACKORDERED");
    expect(screen.getByText("25 results · page 2 of 2")).toBeInTheDocument();
  });

  it("shows an empty state", async () => {
    server.use(http.get(`${API}/api/v1/orders`, () => HttpResponse.json(page([]))));
    renderApp("/orders");
    expect(await screen.findByText("No orders yet")).toBeInTheDocument();
  });

  it("explains an expired session and offers to sign in again", async () => {
    server.use(http.get(`${API}/api/v1/orders`, () => new HttpResponse(null, { status: 401 })));
    const { user, session } = renderApp("/orders");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your session has expired");
    await user.click(screen.getByRole("button", { name: "Sign in again" }));
    expect(session.signIn).toHaveBeenCalledOnce();
  });

  it("offers a retry for server errors and shows the trace reference", async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/api/v1/orders`, () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json(apiError("INTERNAL_ERROR", "The request could not be completed"), {
              status: 500,
            })
          : HttpResponse.json(page([orderSummary()]));
      }),
    );
    const { user } = renderApp("/orders");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Something went wrong");
    expect(alert).toHaveTextContent("Reference: 4bf92f3577b34da6a3ce929d0e0e4736");
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("table", { name: "Sales orders" })).toBeInTheDocument();
  });

  it("hides write actions from a read-only user", async () => {
    server.use(http.get(`${API}/api/v1/orders`, () => HttpResponse.json(page([orderSummary()]))));
    renderApp("/orders", { roles: [] });

    await screen.findByRole("table", { name: "Sales orders" });
    expect(screen.queryByRole("link", { name: /New order/ })).not.toBeInTheDocument();
    expect(screen.getByText("Read-only")).toBeInTheDocument();
  });
});
