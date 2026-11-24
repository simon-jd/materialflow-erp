import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, apiError, APPROVAL_ID, approvalSummary, page } from "@/test/fixtures";
import { renderApp } from "@/test/render";
import { server } from "@/test/server";

const OTHER_APPROVAL = "9f8e7d6c-5b4a-4321-8fed-cba987654321";

function queue(...items: ReturnType<typeof approvalSummary>[]) {
  server.use(http.get(`${API}/api/v1/approvals`, () => HttpResponse.json(page(items))));
}

describe("ApprovalQueuePage", () => {
  it("defaults to pending approvals and offers decisions only where the server allows them", async () => {
    let status: string | null = null;
    server.use(
      http.get(`${API}/api/v1/approvals`, ({ request }) => {
        status = new URL(request.url).searchParams.get("status");
        return HttpResponse.json(
          page([
            approvalSummary(),
            approvalSummary({ approvalId: OTHER_APPROVAL, orderNumber: "SO-MINE", decidableByActor: false }),
          ]),
        );
      }),
    );
    renderApp("/approvals");

    const table = await screen.findByRole("table", { name: "Approvals" });
    expect(status).toBe("PENDING");
    expect(
      within(within(table).getByRole("row", { name: /SO-0B6F7A8E2C1D4E5F8A9B/ })).getByRole("button", {
        name: "Approve",
      }),
    ).toBeInTheDocument();
    expect(within(table).getByRole("row", { name: /SO-MINE/ })).toHaveTextContent("Not assigned to you");
  });

  it("requires a reason to reject and sends it", async () => {
    let body: unknown;
    queue(approvalSummary());
    server.use(
      http.post(`${API}/api/v1/approvals/${APPROVAL_ID}/decisions`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          approvalId: APPROVAL_ID,
          objectId: APPROVAL_ID,
          status: "REJECTED",
          approvedCount: 0,
          version: 1,
        });
      }),
    );
    const { user } = renderApp("/approvals");

    await user.click(await screen.findByRole("button", { name: "Reject" }));
    const dialog = screen.getByRole("dialog", { name: /Reject order/ });
    await user.click(within(dialog).getByRole("button", { name: "Reject order" }));
    expect(within(dialog).getByText("A reason is required to reject an order")).toBeInTheDocument();
    expect(body).toBeUndefined();

    await user.type(within(dialog).getByLabelText("Reason"), "Discount above contract");
    await user.click(within(dialog).getByRole("button", { name: "Reject order" }));
    await expect.poll(() => body).toEqual({ decision: "REJECT", reason: "Discount above contract" });
  });

  it("explains a maker-checker refusal from the server", async () => {
    queue(approvalSummary());
    server.use(
      http.post(`${API}/api/v1/approvals/${APPROVAL_ID}/decisions`, () =>
        HttpResponse.json(
          apiError("MAKER_CHECKER_VIOLATION", "An applicant cannot approve or reject their own request"),
          {
            status: 403,
          },
        ),
      ),
    );
    const { user } = renderApp("/approvals");
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You cannot decide your own order");
  });

  it("shows a read-only user the queue without a decision column", async () => {
    queue(approvalSummary({ decidableByActor: false }));
    renderApp("/approvals", { roles: [] });
    const table = await screen.findByRole("table", { name: "Approvals" });
    expect(within(table).queryByRole("columnheader", { name: "Decision" })).not.toBeInTheDocument();
    expect(within(table).queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows an empty pending queue", async () => {
    queue();
    renderApp("/approvals");
    expect(await screen.findByText("Nothing waiting for approval")).toBeInTheDocument();
  });
});
