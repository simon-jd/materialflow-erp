import { screen } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import {
  API,
  apiError,
  CEMENT_ID,
  CEMENT_LOCATION,
  CUSTOMER_ID,
  order,
  ORDER_ID,
  WAREHOUSE_ID,
} from "@/test/fixtures";
import { renderApp } from "@/test/render";
import { server } from "@/test/server";

const CEMENT_POSITION = `${WAREHOUSE_ID}|${CEMENT_ID}|${CEMENT_LOCATION}`;

interface CapturedPost {
  key: string | null;
  body: unknown;
}

function captureOrderPosts(responses: (() => Response)[]): CapturedPost[] {
  const posts: CapturedPost[] = [];
  server.use(
    http.post(`${API}/api/v1/orders`, async ({ request }) => {
      posts.push({ key: request.headers.get("idempotency-key"), body: await request.json() });
      const respond = responses[Math.min(posts.length - 1, responses.length - 1)];
      if (!respond) throw new Error("no response configured");
      return respond();
    }),
    http.get(`${API}/api/v1/orders/${ORDER_ID}`, () => HttpResponse.json(order({ status: "RESERVED" }))),
  );
  return posts;
}

async function fillValidOrder(user: UserEvent, quantity = "12.5") {
  await screen.findByRole("option", { name: /CUST-001/ });
  await screen.findByRole("option", { name: /CEM-42-5/ });
  await user.selectOptions(screen.getByLabelText("Customer"), CUSTOMER_ID);
  await user.selectOptions(screen.getByLabelText("Stock position"), CEMENT_POSITION);
  await user.type(screen.getByLabelText("Quantity"), quantity);
}

const created = () => HttpResponse.json(order({ status: "RESERVED" }), { status: 201 });
const unavailable = () =>
  HttpResponse.json(
    { error_code: "SHARED_RATE_LIMITER_UNAVAILABLE", message: "unavailable" },
    { status: 503 },
  );

describe("CreateOrderPage", () => {
  it("validates before sending anything", async () => {
    const posts = captureOrderPosts([created]);
    const { user } = renderApp("/orders/new");
    await screen.findByRole("option", { name: /CUST-001/ });

    await user.type(screen.getByLabelText("Quantity"), "0");
    await user.click(screen.getByRole("button", { name: "Submit order" }));

    expect(await screen.findByText("Choose a customer")).toBeInTheDocument();
    expect(screen.getByText("Choose a stock position")).toBeInTheDocument();
    expect(screen.getByText("Quantity must be greater than zero")).toBeInTheDocument();
    expect(screen.getByLabelText("Customer")).toHaveAttribute("aria-invalid", "true");
    expect(posts).toHaveLength(0);
  });

  it("warns when the quantity exceeds what is available now", async () => {
    captureOrderPosts([created]);
    const { user } = renderApp("/orders/new");
    await fillValidOrder(user, "1000");
    expect(
      await screen.findByText(/Only 960 available now; the rest will be backordered/),
    ).toBeInTheDocument();
  });

  it("submits the order with an Idempotency-Key and opens the created order", async () => {
    const posts = captureOrderPosts([created]);
    const { user, router } = renderApp("/orders/new");
    await fillValidOrder(user);

    await user.click(screen.getByRole("button", { name: "Submit order" }));

    expect(await screen.findByText("Order submitted")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
    expect(posts).toHaveLength(1);
    expect(posts[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(posts[0]?.body).toEqual({
      customerId: CUSTOMER_ID,
      allowPartialFulfillment: true,
      lines: [
        { warehouseId: WAREHOUSE_ID, materialId: CEMENT_ID, locationId: CEMENT_LOCATION, quantity: 12.5 },
      ],
    });
  });

  it("retries the same submission with the same Idempotency-Key", async () => {
    // Three transient failures exhaust the automatic retries; the user then submits again.
    const posts = captureOrderPosts([unavailable, unavailable, unavailable, created]);
    const { user } = renderApp("/orders/new");
    await fillValidOrder(user);

    await user.click(screen.getByRole("button", { name: "Submit order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Changes are temporarily paused");
    await user.click(screen.getByRole("button", { name: "Submit order" }));

    expect(await screen.findByText("Order submitted")).toBeInTheDocument();
    expect(posts).toHaveLength(4);
    expect(new Set(posts.map((post) => post.key)).size).toBe(1);
  });

  it("uses a new Idempotency-Key once the values change", async () => {
    const posts = captureOrderPosts([unavailable, unavailable, unavailable, created]);
    const { user } = renderApp("/orders/new");
    await fillValidOrder(user, "5");

    await user.click(screen.getByRole("button", { name: "Submit order" }));
    await screen.findByRole("alert");
    await user.type(screen.getByLabelText("Quantity"), "0");
    await user.click(screen.getByRole("button", { name: "Submit order" }));

    await screen.findByText("Order submitted");
    expect(posts[0]?.key).not.toBe(posts[3]?.key);
    expect(posts[3]?.body).toMatchObject({ lines: [{ quantity: 50 }] });
  });

  it("maps a business conflict and server field errors onto the form", async () => {
    captureOrderPosts([
      () =>
        HttpResponse.json(apiError("CREDIT_LIMIT_CONFLICT", "Credit limit would be exceeded"), {
          status: 409,
        }),
      () =>
        HttpResponse.json(
          apiError("VALIDATION_FAILED", "Request validation failed", {
            "lines[0].quantity": "must be greater than 0",
          }),
          { status: 400 },
        ),
    ]);
    const { user } = renderApp("/orders/new");
    await fillValidOrder(user);

    await user.click(screen.getByRole("button", { name: "Submit order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Credit limit would be exceeded");

    await user.click(screen.getByRole("button", { name: "Submit order" }));
    await expect
      .poll(() => screen.getByLabelText("Quantity"))
      .toHaveAccessibleDescription("must be greater than 0");
    expect(screen.getByLabelText("Quantity")).toHaveAttribute("aria-invalid", "true");
  });

  it("explains a server-side 403 even if the UI allowed the attempt", async () => {
    captureOrderPosts([
      () =>
        HttpResponse.json(apiError("ACCESS_DENIED", "The operation is outside the actor's scope"), {
          status: 403,
        }),
    ]);
    const { user } = renderApp("/orders/new");
    await fillValidOrder(user);
    await user.click(screen.getByRole("button", { name: "Submit order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You do not have permission to do this");
  });

  it("does not render the form for a read-only user", async () => {
    const posts = captureOrderPosts([created]);
    renderApp("/orders/new", { roles: [] });
    expect(await screen.findByText("You cannot create orders")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit order" })).not.toBeInTheDocument();
    expect(posts).toHaveLength(0);
  });
});
