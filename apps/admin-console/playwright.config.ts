import { defineConfig, devices } from "@playwright/test";

/**
 * Runs against a real stack (Keycloak, gateway, erp-core, PostgreSQL, the console image); see
 * e2e/README.md. Nothing is mocked: sign-in goes through the Keycloak login page.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [
    ["list"],
    ["html", { open: "never" }],
    ["json", { outputFile: "test-results/e2e-results.json" }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:28088",
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } },
  ],
});
