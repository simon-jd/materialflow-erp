import { expect, test } from "@playwright/test";
import { createOrder, selectStock, signIn, users } from "./support";

const OUTPUT = process.env.SCREENSHOT_DIR ?? "../../docs/images";

test.skip(!process.env.CAPTURE_SCREENSHOTS, "Set CAPTURE_SCREENSHOTS=1 to refresh the README screenshots");

test("capture README screenshots", async ({ browser }) => {
  const maker = await (await browser.newContext()).newPage();
  await signIn(maker, users.sales);
  await createOrder(maker, "40");
  // 600 x 47.25 is above the 25,000 approval threshold, and only 500 are on hand.
  const { orderNumber, orderId } = await createOrder(maker, "600", "PLY-18-2440");

  const page = await (await browser.newContext()).newPage();
  await signIn(page, users.approver);
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Approvals" }).click();
  await page
    .getByRole("row", { name: new RegExp(orderNumber) })
    .getByRole("button", { name: "Approve" })
    .click();
  await expect(page.getByRole("row", { name: new RegExp(orderNumber) })).toHaveCount(0);

  const ordersLink = page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Orders" });
  await ordersLink.click();
  await expect(ordersLink).toHaveAttribute("aria-current", "page");
  // The list first shows the cached page, then the background refetch after the approval lands.
  await expect(page.getByRole("row", { name: new RegExp(orderNumber) })).toContainText("Partially allocated");
  await page.screenshot({ path: `${OUTPUT}/admin-console-orders.png`, animations: "disabled" });

  await page.goto(`/orders/${orderId}`);
  await expect(page.getByText("1 of 1 approvals (Any)")).toBeVisible();
  await page.screenshot({
    path: `${OUTPUT}/admin-console-order-detail.png`,
    fullPage: true,
    animations: "disabled",
  });

  await page.goto("/orders/new");
  await page
    .getByLabel("Customer", { exact: true })
    .selectOption({ label: "CUST-001 · Cedar Ridge Contractors" });
  await selectStock(page, "CEM-42-5");
  await page.getByLabel("Quantity").fill("1200");
  await page.getByLabel("Quantity").blur();
  await expect(page.getByText(/available now/)).toBeVisible();
  await page.screenshot({
    path: `${OUTPUT}/admin-console-create-order.png`,
    fullPage: true,
    animations: "disabled",
  });
});
