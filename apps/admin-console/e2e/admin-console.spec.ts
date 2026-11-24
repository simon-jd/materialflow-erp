import { expect, test } from "@playwright/test";
import { accessToken, createOrder, signIn, users } from "./support";

test("a sales user creates an order and finds it in the list and the detail", async ({ page }) => {
  await signIn(page, users.sales);

  const { orderNumber, orderId } = await createOrder(page, "2");
  await expect(page.getByRole("row", { name: /CEM-42-5/ })).toContainText("Allocated");

  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Orders" }).click();
  const row = page.getByRole("row", { name: new RegExp(orderNumber) });
  await expect(row).toContainText("Cedar Ridge Contractors");
  await expect(row).toContainText("Reserved");

  await row.getByRole("link", { name: orderNumber }).click();
  await expect(page).toHaveURL(`/orders/${orderId}`);
  await expect(page.getByRole("heading", { name: `Order ${orderNumber}` })).toBeVisible();
});

test("a read-only user can browse but cannot create, and the API refuses as well", async ({
  page,
  request,
}) => {
  await signIn(page, users.readonly);
  await expect(page.getByRole("list", { name: "Roles" })).toContainText("Read-only");
  await expect(page.getByRole("link", { name: "New order" })).toHaveCount(0);

  await page.goto("/orders/new");
  await expect(page.getByText("You cannot create orders")).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit order" })).toHaveCount(0);

  // Hiding the button is a convenience; the server is the boundary.
  const response = await request.post("/api/v1/orders", {
    headers: { Authorization: `Bearer ${await accessToken(page)}`, "Idempotency-Key": crypto.randomUUID() },
    data: {
      customerId: "33333333-3333-3333-3333-333333333333",
      allowPartialFulfillment: true,
      lines: [
        {
          materialId: "55555555-5555-5555-5555-555555555555",
          warehouseId: "22222222-2222-2222-2222-222222222222",
          locationId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1",
          quantity: 1,
        },
      ],
    },
  });
  expect(response.status()).toBe(403);
  expect(((await response.json()) as { errorCode: string }).errorCode).toBe("ACCESS_DENIED");
});

test("an approver approves a large order that its maker cannot decide", async ({ browser }) => {
  const maker = await (await browser.newContext()).newPage();
  await signIn(maker, users.sales);
  const { orderNumber, orderId } = await createOrder(maker, "3000");
  await expect(maker.getByText("It needs approval before stock is reserved.")).toBeVisible();
  await expect(maker.getByText("0 of 1 approvals (Any)")).toBeVisible();
  await expect(maker.getByRole("button", { name: "Approve" })).toHaveCount(0);

  const checker = await (await browser.newContext()).newPage();
  await signIn(checker, users.approver);
  await checker.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Approvals" }).click();
  const pending = checker.getByRole("row", { name: new RegExp(orderNumber) });
  await pending.getByRole("button", { name: "Approve" }).click();
  await expect(pending).toHaveCount(0);

  await checker.goto(`/orders/${orderId}`);
  await expect(checker.getByText("1 of 1 approvals (Any)")).toBeVisible();
  await expect(checker.getByRole("list", { name: "Decisions" })).toContainText("Approved by you");
  // 3,000 bags against roughly 1,000 on hand: the rest is backordered.
  await expect(checker.getByText("Partially allocated")).toBeVisible();
  await expect(checker.getByText("Partially backordered")).toBeVisible();
});

test("every screen renders under the production CSP without console errors", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  page.on("pageerror", (error) => errors.push(error.message));

  await signIn(page, users.approver);
  for (const [path, heading] of [
    ["/approvals", "Approval queue"],
    ["/inventory", "Inventory availability"],
    ["/orders/new", "New sales order"],
    ["/orders", "Sales orders"],
  ] as const) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
    await expect(page.getByRole("status")).toHaveCount(0);
  }
  expect(errors).toEqual([]);
});
