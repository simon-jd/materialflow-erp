import { expect, type Page } from "@playwright/test";

export interface SyntheticUser {
  username: string;
  password: string;
}

// Synthetic fixtures from deploy/compose/keycloak/materialflow-realm.json; local test stacks only.
export const users = {
  /** SALES_ORDER_WRITE, user 66666666-...-601: the maker. */
  sales: { username: "synthetic-sales", password: "synthetic-sales-only" },
  /** Every role, user 66666666-...-602: the frozen approver of the seeded sales-order template. */
  approver: { username: "synthetic-demo", password: "synthetic-demo-only" },
  /** No roles. */
  readonly: { username: "synthetic-readonly", password: "synthetic-readonly-only" },
} satisfies Record<string, SyntheticUser>;

/** Signs in through the real Keycloak login page (Authorization Code + PKCE). */
export async function signIn(page: Page, user: SyntheticUser): Promise<void> {
  await page.goto("/orders");
  await page.locator("#username").fill(user.username);
  await page.locator("#password").fill(user.password);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(page.getByRole("heading", { name: "Sales orders" })).toBeVisible();
}

export interface CreatedOrder {
  orderId: string;
  orderNumber: string;
}

/** Fills the create form for CUST-001 and one stock position, submits it and waits for the detail. */
export async function createOrder(page: Page, quantity: string, sku = "CEM-42-5"): Promise<CreatedOrder> {
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Orders" }).click();
  await page.getByRole("link", { name: "New order" }).click();
  const customer = page.getByLabel("Customer", { exact: true });
  await expect(customer.locator("option", { hasText: "CUST-001" })).toBeAttached();
  await customer.selectOption({ label: "CUST-001 · Cedar Ridge Contractors" });
  await selectStock(page, sku);
  await page.getByLabel("Quantity").fill(quantity);
  await page.getByRole("button", { name: "Submit order" }).click();

  await expect(page.getByText("Order submitted")).toBeVisible();
  const heading = page.getByRole("heading", { level: 1 });
  await expect(heading).toHaveText(/^Order SO-[0-9A-F]+$/);
  const orderNumber = (await heading.innerText()).replace(/^Order /, "");
  const orderId = new URL(page.url()).pathname.split("/").at(-1) ?? "";
  return { orderId, orderNumber };
}

export async function selectStock(page: Page, sku: string): Promise<void> {
  const select = page.getByLabel("Stock position");
  const option = select.locator("option", { hasText: sku });
  await expect(option).toBeAttached();
  await select.selectOption((await option.getAttribute("value")) ?? "");
}

/** The OIDC user object oidc-client-ts keeps in sessionStorage. */
export async function accessToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const key = Object.keys(sessionStorage).find((name) => name.startsWith("oidc.user:"));
    const stored = key ? sessionStorage.getItem(key) : null;
    return stored ? (JSON.parse(stored) as { access_token?: string }).access_token : undefined;
  });
  if (!token) throw new Error("No OIDC user in sessionStorage");
  return token;
}
