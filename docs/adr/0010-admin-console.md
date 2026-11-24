# ADR 0010: Admin console on the existing API

Status: accepted

## Context

MaterialFlow exposed a JWT-secured, multi-tenant REST API with no user interface, and the API had only
write endpoints plus a single-order read. Operators need to list orders, inspect allocation and
approval state, submit orders and decide approvals. The console must not weaken the API's security
model: identity, organization and roles come from Keycloak tokens, and the server enforces every rule.

## Decision

**Stack.** Vite, React 19 and TypeScript in strict mode (`strict`, `noUncheckedIndexedAccess`,
`exactOptionalPropertyTypes`), React Router data routes, and TanStack Query for all server state.
There is no client-side store: the API is the source of truth, queries are keyed by their parameters,
and mutations invalidate the lists they affect. Queries retry only transient failures (network, 429,
5xx); a 4xx is final. Forms use react-hook-form with zod schemas that mirror the server's rules (for
example, one line per stock position, at most six decimals) for fast feedback; the server stays
authoritative and its field errors are mapped back onto the form.

**Typed client from the contract.** `openapi-typescript` generates types from
`contracts/openapi/materialflow-erp-v1.yaml` and `openapi-fetch` (about 6 kB) uses them, so a path,
parameter or schema change fails `tsc`. CI regenerates and fails on drift (`npm run check:api`). The
hooks around the client are hand-written and short; orval was not chosen because generating hooks as
well adds code that has to be reviewed but does not add safety.

**UI kit.** Tailwind CSS v4 with shadcn/ui-style components (class-variance-authority + tailwind-merge)
built on native elements: `<select>`, `<dialog>`, checkboxes. Radix portals and Mantine inject
`<style>` elements at runtime, which needs `style-src 'unsafe-inline'` or per-response nonces. The
production CSP has neither inline scripts nor inline styles.

**Sign-in.** OIDC Authorization Code with PKCE (S256) through `oidc-client-ts`, against a public
Keycloak client, `materialflow-admin-console`, that has exact redirect URIs and web origins (no
wildcards) and no password grant.

1. An unauthenticated visit redirects to Keycloak with a PKCE challenge and the requested path in
   `state`.
2. Keycloak redirects back to `/auth/callback`. The code is exchanged for tokens, the URL is cleaned,
   and the user lands on the original path. Only same-origin paths are accepted from `state`.
3. Tokens are kept in `sessionStorage` (per tab) and renewed with the refresh token, so no hidden
   iframe or `frame-src` exception is needed.
4. Every API call carries `Authorization: Bearer <access token>` and nothing else about identity.
   The UI reads `user_id`, `organization_id` and a `roles` claim from the ID token only to hide
   actions; the gateway and erp-core validate the access token and derive the actor from its claims.
   `X-Organization-Id`/`X-Actor-Id` became optional because JWT callers had to send headers that the
   server ignored. The nginx proxy also clears them.

**Same-origin `/api`.** In AWS, CloudFront serves `dist/` and routes `/api/*` to the gateway; in
Compose and Helm, the console's nginx proxies `/api/` to the gateway. The browser makes no
cross-origin API calls in any deployed environment. As a result there is no CORS preflight before each
non-simple request, no CORS policy to keep correct in production, and `connect-src` is just `'self'` plus
the identity provider. The gateway's CORS support exists only for the Vite dev server: it is
configured from properties, accepts only exact `scheme://host[:port]` origins (a wildcard fails
startup), runs before authentication so preflights succeed, and never allows credentials. A same-origin
router must forward the browser's `Host`, or leave the allow-list empty: browsers send `Origin` on
same-origin POSTs, and a rewritten `Host` makes the gateway treat them as cross-origin. The first
full-stack e2e run caught exactly this, as an empty 403 on every POST through nginx.

**Runtime configuration.** `index.html` loads `/config.js` before the bundle. The container generates
it from `OIDC_AUTHORITY`, `OIDC_CLIENT_ID`, `API_BASE_URL` and `API_UPSTREAM` at start, together with
the CSP, so one image serves every environment. The entrypoint rejects values that are not plain URLs
or identifiers, and the browser validates the object with zod and shows a configuration error instead
of failing later.

**Read endpoints.** The console needed list reads that the API lacked: orders, the sales-order approval
queue and detail, inventory availability and reference data. They follow the existing pattern
(controller, `*ApplicationService`, JDBC, `ApiExceptionHandler`) and are tenant-scoped from the token.
They use offset pagination with a stable order (`created_at DESC, id DESC`), a page size capped at 100,
a count and the page read in one `REPEATABLE READ` snapshot, and composite indexes that lead with
`organization_id`. Operators page by number with a total, which offset paging supports; keyset
pagination is the next step if deep pages become hot. The approval list returns `decidableByActor`,
computed in SQL from the frozen approver set, maker-checker and earlier decisions, so the UI does not
duplicate those rules; the decision endpoint still enforces them.

**Idempotent submits.** Each submission attempt gets one `Idempotency-Key`. Automatic retries of
transient failures and a manual resubmit of the same values reuse it, so the server replays the first
result. Changed values start a new attempt with a new key, which avoids `IDEMPOTENCY_KEY_REUSED`.

## Consequences

- Script running in the page could read tokens from `sessionStorage`. The mitigations are a strict CSP
  (`script-src 'self'`, no inline script, `frame-ancestors 'none'`) and short-lived access tokens. A
  backend-for-frontend with `HttpOnly` session cookies would keep tokens out of JavaScript entirely,
  at the cost of another service and CSRF handling. That is the upgrade path if the threat model
  requires it.
- The console needs JWT mode (`MATERIALFLOW_SECURITY_ENABLED=true`). Local header-based mode remains
  for scripts and load tests.
- Error bodies are the API's existing `ApiError` (`errorCode`, `message`, `fieldErrors`, `traceId`)
  rather than RFC 7807. The client normalizes both forms, plus the gateway's own JSON and empty-bodied
  401/429 responses, so the API can migrate to `application/problem+json` without UI changes.
- Hiding an action is not authorization. A component test and a Playwright test check that a
  read-only user sees no create action, and that the API answers that user's direct POST with 403.
