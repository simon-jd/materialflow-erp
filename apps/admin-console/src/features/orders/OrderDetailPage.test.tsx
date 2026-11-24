import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, apiError, APPROVAL_ID, approvalSummary, order, ORDER_ID } from "@/test/fixtures";
import { renderApp } from "@/test/render";
import { server } from "@/test/server";

describe("OrderDetailPage", () => {
  it("shows lines with allocation and backorder state", async () => {
    server.use(http.get(`${API}/api/v1/orders/${ORDER_ID}`, () => HttpResponse.json(order())));
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByRole("heading", { name: "Order SO-0B6F7A8E2C1D4E5F8A9B" })).toBeInTheDocument();
    expect(screen.getByText("Partially allocated")).toBeInTheDocument();
    expect(screen.getByText("104 of 110")).toBeInTheDocument();
    const lines = screen.getByRole("table", { name: "Order lines" });
    const plywood = await within(lines).findByRole("row", { name: /PLY-18-2440/ });
    expect(plywood).toHaveTextContent("Structural Plywood");
    expect(plywood).toHaveTextContent("WH-WEST-01");
    expect(plywood).toHaveTextContent("Partially backordered");
    expect(within(lines).getByRole("row", { name: /CEM-42-5/ })).toHaveTextContent("Allocated");
  });

  it("shows the approval and lets an assigned approver approve", async () => {
    let decision: unknown;
    let approved = false;
    server.use(
      http.get(`${API}/api/v1/orders/${ORDER_ID}`, () =>
        HttpResponse.json(
          order({ status: approved ? "RESERVED" : "PENDING_APPROVAL", approvalInstanceId: APPROVAL_ID }),
        ),
      ),
      http.get(`${API}/api/v1/approvals/${APPROVAL_ID}`, () =>
        HttpResponse.json({
          approval: approvalSummary(
            approved ? { status: "APPROVED", approvedCount: 1, decidableByActor: false } : {},
          ),
          decisions: approved
            ? [
                {
                  approverId: "66666666-6666-6666-6666-666666666602",
                  decision: "APPROVE",
                  reason: null,
                  decidedAt: "2026-11-23T11:05:00Z",
                },
              ]
            : [],
        }),
      ),
      http.post(`${API}/api/v1/approvals/${APPROVAL_ID}/decisions`, async ({ request }) => {
        decision = await request.json();
        approved = true;
        return HttpResponse.json({
          approvalId: APPROVAL_ID,
          objectId: ORDER_ID,
          status: "APPROVED",
          approvedCount: 1,
          version: 1,
        });
      }),
    );
    const { user } = renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText("0 of 1 approvals (Any)")).toBeInTheDocument();
    expect(screen.getAllByText("Awaiting approval")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("listitem")).toHaveTextContent(/^Approve by 66666666 on /);
    expect(decision).toEqual({ decision: "APPROVE" });
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("does not offer approval actions to a user without the approval role", async () => {
    server.use(
      http.get(`${API}/api/v1/orders/${ORDER_ID}`, () =>
        HttpResponse.json(order({ status: "PENDING_APPROVAL", approvalInstanceId: APPROVAL_ID })),
      ),
      http.get(`${API}/api/v1/approvals/${APPROVAL_ID}`, () =>
        HttpResponse.json({ approval: approvalSummary(), decisions: [] }),
      ),
    );
    renderApp(`/orders/${ORDER_ID}`, { roles: [] });
    expect(await screen.findByText("0 of 1 approvals (Any)")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("explains a missing or foreign order", async () => {
    server.use(
      http.get(`${API}/api/v1/orders/${ORDER_ID}`, () =>
        HttpResponse.json(apiError("RESOURCE_NOT_FOUND", "Sales order was not found"), { status: 404 }),
      ),
    );
    renderApp(`/orders/${ORDER_ID}`);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "It does not exist or belongs to another organization.",
    );
  });

  it("rejects malformed order ids without calling the API", async () => {
    renderApp("/orders/not-an-id");
    expect(await screen.findByText(/is not a valid order reference/)).toBeInTheDocument();
  });
});
