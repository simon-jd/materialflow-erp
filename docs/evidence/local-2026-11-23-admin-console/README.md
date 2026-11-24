# Admin console full-stack verification: November 23–24, 2026

This verifies the React admin console (`apps/admin-console`) and the read endpoints added for it
against the real local stack. All data and users are synthetic, everything ran on one developer
machine, and nothing was deployed. Labels follow the repository convention: **measured** values
were observed in this run; **derived** values are calculated from measured ones.

## What was checked

| Check | Result | Label | Evidence |
|---|---|---|---|
| Maven reactor with the quality profile | 73 JUnit tests in 24 suites, 0 failures, errors or skips; 0 Checkstyle violations; 0 SpotBugs bugs | measured | [verification.json](verification.json) |
| New backend tests | 5 HTTP-level erp-core tests with signed JWTs (paging, tenant isolation against spoofed headers, read-only role, approval queue flags, warehouse scope) and 12 gateway tests (CORS origin validation, preflight before authentication, routes) | measured | [verification.json](verification.json) |
| Console unit and component tests | 64 Vitest tests in 10 files; 86.6% line and 82.8% branch coverage | measured | [verification.json](verification.json) |
| Typecheck, lint, build, generated-client drift check | all passed | measured | [verification.json](verification.json) |
| OpenAPI lint, Compose config, Helm lint and template | all passed (one pre-existing Redocly warning) | measured | [verification.json](verification.json) |
| Playwright e2e on a freshly created stack | 4 passed, 0 failed, 0 retries; the suite took 8.6 s | measured | [e2e-summary.json](e2e-summary.json) |

The e2e run used no mocks. Each test signs in on the Keycloak login page (Authorization Code +
PKCE), and requests go from the console's nginx through the gateway to erp-core and PostgreSQL:

1. `synthetic-sales` creates an order with the form, which opens its detail page. The order is then
   found in the list and opened again from there.
2. `synthetic-readonly` sees "Read-only" and no create action, gets the "cannot create" screen at
   `/orders/new`, and a direct `POST /api/v1/orders` with that user's token returns 403
   `ACCESS_DENIED`.
3. `synthetic-sales` submits 3,000 bags (31,443.75, above the 25,000 threshold). The maker is offered
   no decision. `synthetic-demo` approves it from the queue, and the order becomes
   `PARTIALLY_ALLOCATED` with the rest backordered.
4. Every screen renders under the production Content-Security-Policy with no console errors or CSP
   violations.

After the run, the database contained exactly the two orders the tests created, both with applicant
`…601`, and one `APPROVE` decision by `…602`. There were two order idempotency records and no order
from the read-only user. The console proxy logged 2 × `POST 201`, 1 × `POST 200` (decision),
1 × `POST 403` (read-only) and no 5xx responses.

## Findings

- **Fixed: every POST through the container proxy returned an empty 403** on the first full-stack
  run. Browsers send `Origin` even on same-origin POSTs. nginx rewrote `Host` to
  `edge-gateway:8081`, so the gateway treated the request as cross-origin and rejected an origin
  that was not on its dev allow-list. The proxy now forwards the original `Host`. Unit tests, which
  run against MSW, could not have caught this. The same applies to any same-origin router
  (CloudFront): either forward the viewer `Host` or leave `MATERIALFLOW_CORS_ALLOWED_ORIGINS` empty
  in that environment (the default), which turns CORS processing off.
- Two test-only problems were fixed before the recorded run: an ambiguous `getByLabel("Customer")`
  that matched both the search box and the select, and a helper that looked for "New order" on a
  page that does not have it. Separately, a list screenshot was taken while a background refetch
  was still in flight, so the capture now waits for the refreshed row.

## Environment

- Apple Silicon Mac, macOS 27.0.1, Docker Desktop engine 29.8.0. Docker had 12 CPUs and 12.5 GB of
  memory, shared with three other agents; the heavy-Docker lock was held during the stack runs.
- Images: `quay.io/keycloak/keycloak:26.3.3`, `postgres:17.11-alpine`, `apache/kafka:4.1.2`,
  `redis:8.2.1-alpine`, and `nginxinc/nginx-unprivileged:1.30-alpine` (nginx 1.30.6) for the
  console. erp-core and the gateway were packaged from the JARs just built (`Dockerfile.runtime`)
  and limited to 2 CPU / 1 GiB each by the qualification overlay. The console was limited to
  0.5 CPU / 256 MiB.
- Console container: user 101, read-only root filesystem, `no-new-privileges`, 91.2 MB image,
  13.3 MiB memory in use after the run (measured once, `docker stats`).
- Tooling: OpenJDK 21.0.13.1, Maven 3.9.16, Node 26.12.1 locally (CI uses 24), npm 11.20.0,
  Playwright 1.65.1 with Chromium 159.0.8154.6 (headless, 1440×900).
- Stack start-up (`stack.sh up`, all health checks green): 44 s for the recorded run and 35–38 s on
  earlier fresh starts the same day, including cached image builds. Single observations; this is
  not a benchmark.

## Reproduce

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -B -ntp -Pquality verify                          # also produces the JARs the stack packages

cd apps/admin-console
npm ci
npm run check:api && npm run typecheck && npm run lint && npm test && npm run build
npx playwright install chromium
cd ../..

E2E_PROJECT=mf-admin-console E2E_TAG=admin-console apps/admin-console/e2e/stack.sh up
(cd apps/admin-console && E2E_BASE_URL=http://localhost:28088 npx playwright test)
E2E_PROJECT=mf-admin-console E2E_TAG=admin-console apps/admin-console/e2e/stack.sh down

# README screenshots: repeat on a freshly created stack with
(cd apps/admin-console && CAPTURE_SCREENSHOTS=1 E2E_BASE_URL=http://localhost:28088 npx playwright test screenshots)
```

The stack uses the loopback-only qualification ports from `deploy/compose/qualification.env`
(console 28088, Keycloak 28180, gateway 28081). The Keycloak client registers exact redirect URIs
for ports 5173, 8088 and 28088 only, so the console must use one of them. The Playwright JSON and
HTML reports, the database snapshot and the proxy status counts are under `raw/`, which git
ignores.
