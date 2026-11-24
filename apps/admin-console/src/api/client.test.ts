import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, page } from "@/test/fixtures";
import { server } from "@/test/server";
import { createApiClient, unwrap } from "./client";
import { ApiProblem } from "./problem";

describe("createApiClient", () => {
  it("sends the bearer token and never sends spoofable identity headers", async () => {
    let headers: Headers | undefined;
    server.use(
      http.get(`${API}/api/v1/orders`, ({ request }) => {
        headers = request.headers;
        return HttpResponse.json(page([]));
      }),
    );
    const api = createApiClient({ baseUrl: API, getAccessToken: () => Promise.resolve("token-123") });

    await api.GET("/api/v1/orders", { params: { query: { page: 0, size: 20 } } });

    expect(headers?.get("authorization")).toBe("Bearer token-123");
    for (const name of ["x-organization-id", "x-actor-id", "x-roles", "x-warehouse-scope"]) {
      expect(headers?.has(name)).toBe(false);
    }
  });

  it("omits the Authorization header when there is no token", async () => {
    let authorization: string | null = "unset";
    server.use(
      http.get(`${API}/api/v1/orders`, ({ request }) => {
        authorization = request.headers.get("authorization");
        return HttpResponse.json(page([]));
      }),
    );
    await createApiClient({ baseUrl: API, getAccessToken: () => Promise.resolve(undefined) }).GET(
      "/api/v1/orders",
    );
    expect(authorization).toBeNull();
  });

  it("unwrap throws an ApiProblem for error responses", async () => {
    server.use(http.get(`${API}/api/v1/orders`, () => new HttpResponse(null, { status: 401 })));
    const api = createApiClient({ baseUrl: API, getAccessToken: () => Promise.resolve("expired") });

    const result = await api.GET("/api/v1/orders");

    expect(() => unwrap(result)).toThrow(ApiProblem);
    expect(() => unwrap(result)).toThrow("HTTP 401");
  });
});
