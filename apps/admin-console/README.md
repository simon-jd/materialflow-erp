# MaterialFlow admin console

React + TypeScript operator console for the MaterialFlow API. It provides an order list, order
detail (allocation, backorders and approval history), order entry, the maker-checker approval queue
and inventory availability. It signs in with OIDC Authorization Code + PKCE against Keycloak, sends
only a bearer token, and hides actions that the user's roles do not allow. The API enforces the same
rules on its own. Design notes are in [ADR 0010](../../docs/adr/0010-admin-console.md).

Stack: Vite, React 19, TypeScript (strict), React Router, TanStack Query, openapi-typescript +
openapi-fetch, react-hook-form + zod, Tailwind CSS with shadcn/ui-style components, oidc-client-ts,
and Vitest + Testing Library + MSW + Playwright. Requires Node.js 22.22 or later (CI uses 24).

## Run locally

The console needs the JWT-enabled stack. From the repository root:

```bash
MATERIALFLOW_SECURITY_ENABLED=true docker compose -f deploy/compose/compose.yml up -d --build

cd apps/admin-console
npm ci
npm run dev            # http://localhost:5173, calls the gateway on :8081 (CORS allows exactly this origin)
```

Sign in as one of the synthetic users in `deploy/compose/keycloak/materialflow-realm.json`:

| User | Roles | Use |
|---|---|---|
| `synthetic-sales` | `SALES_ORDER_WRITE` | Creates orders (the maker). |
| `synthetic-demo` | All roles | The frozen approver of the seeded sales-order template (the checker). |
| `synthetic-readonly` | None | Can browse but has no write actions; the API returns 403 on writes. |

Orders above 25,000 or above the discount threshold wait in the approval queue. The maker cannot
approve their own order.

To run the container instead of the dev server, add `--profile console` to the Compose command and
open <http://localhost:8088>. nginx serves the SPA and proxies `/api/` to the gateway on the same
origin.

## Configure

`index.html` loads `/config.js` before the bundle:

```js
window.__MATERIALFLOW_CONFIG__ = { apiBaseUrl: "", oidcAuthority: "https://id.example.com/realms/materialflow", oidcClientId: "materialflow-admin-console" };
```

| Key | Container env var | Meaning |
|---|---|---|
| `apiBaseUrl` | `API_BASE_URL` | API origin. Leave it empty when `/api/*` is served from the console's origin (CloudFront, an ingress or the nginx proxy). |
| `oidcAuthority` | `OIDC_AUTHORITY` | Browser-facing realm URL. The token issuer must match the gateway's `OIDC_ISSUER_URI`. |
| `oidcClientId` | `OIDC_CLIENT_ID` | Public client with this origin's exact `/auth/callback` redirect URI and web origin. |
| | `API_UPSTREAM` | Optional. The container proxies `/api/` here, for example `http://edge-gateway:8081`. Without it, `/api/` returns 404. |

The image writes `/config.js` and the Content-Security-Policy (`connect-src` includes the OIDC origin)
at start and refuses to start on malformed values. In development, Vite serves `/config.js` from
`MATERIALFLOW_API_BASE_URL`, `MATERIALFLOW_OIDC_AUTHORITY` and `MATERIALFLOW_OIDC_CLIENT_ID`, which
default to the local stack. `npm run build` output contains no environment values.

Static hosting (S3/CloudFront): upload `dist/`, serve `index.html` for unknown paths (SPA fallback),
supply `/config.js` per environment with `Cache-Control: no-store`, route `/api/*` to the gateway on
the same origin, cache `assets/*` as immutable, and send the same security headers as
[`docker/40-materialflow-runtime-config.sh`](docker/40-materialflow-runtime-config.sh).

## Test

```bash
npm run generate:api   # after changing contracts/openapi/materialflow-erp-v1.yaml (CI runs check:api)
npm run typecheck
npm run lint           # ESLint strict-type-checked + Prettier
npm test               # Vitest + Testing Library + MSW, with coverage
npm run build
```

The component tests render the real route tree against MSW handlers with a synthetic session. They
cover the order list, creation, detail, approvals, inventory, the mapping of every error status to a
message, `Idempotency-Key` reuse on retry, and role gating.

### End to end

`e2e/` runs Playwright against the real stack: it signs in on the Keycloak page, creates an order and
finds it in the list and the detail, has a checker approve a maker's large order, and checks that the
read-only user has no create action and gets 403 from the API. The stack uses the loopback-only
qualification ports and its own Compose project:

```bash
mvn -B -ntp package -DskipTests                      # from the repository root
apps/admin-console/e2e/stack.sh up                   # 8 containers; prints E2E_BASE_URL
cd apps/admin-console && npx playwright install chromium
E2E_BASE_URL=http://localhost:28088 npx playwright test
CAPTURE_SCREENSHOTS=1 npx playwright test screenshots   # refreshes docs/images/admin-console-*.png
e2e/stack.sh down                                    # removes containers and volumes
```

The `admin-console-e2e` workflow runs the same steps on demand (`workflow_dispatch`).
