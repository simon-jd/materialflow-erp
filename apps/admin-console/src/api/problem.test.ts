import { describe, expect, it } from "vitest";
import { apiError } from "@/test/fixtures";
import { ApiProblem, describeProblem, isRetryable, toApiProblem, toProblem } from "./problem";

describe("toApiProblem", () => {
  it("reads the API's stable error body", () => {
    const problem = toApiProblem(
      400,
      apiError("VALIDATION_FAILED", "Request validation failed", { customerId: "must not be null" }),
    );
    expect(problem).toMatchObject({
      status: 400,
      code: "VALIDATION_FAILED",
      detail: "Request validation failed",
      fieldErrors: { customerId: "must not be null" },
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
    });
  });

  it("reads RFC 7807 problem documents", () => {
    const problem = toApiProblem(404, {
      type: "about:blank",
      title: "Not Found",
      status: 404,
      detail: "Sales order was not found",
      instance: "/api/v1/orders/x",
    });
    expect(problem).toMatchObject({
      status: 404,
      detail: "Sales order was not found",
      traceId: "/api/v1/orders/x",
    });
  });

  it("reads the gateway's snake_case error and tolerates empty or non-JSON bodies", () => {
    expect(toApiProblem(503, { error_code: "SHARED_RATE_LIMITER_UNAVAILABLE", message: "x" }).code).toBe(
      "SHARED_RATE_LIMITER_UNAVAILABLE",
    );
    expect(toApiProblem(401, "")).toMatchObject({ status: 401, code: undefined, fieldErrors: {} });
    expect(toApiProblem(502, "<html>Bad gateway</html>").status).toBe(502);
  });
});

describe("describeProblem", () => {
  it.each([
    [400, "VALIDATION_FAILED", "Some values were rejected", false],
    [401, undefined, "Your session has expired", false],
    [403, "ACCESS_DENIED", "You do not have permission to do this", false],
    [403, "MAKER_CHECKER_VIOLATION", "You cannot decide your own order", false],
    [404, "RESOURCE_NOT_FOUND", "Not found", false],
    [409, "IDEMPOTENCY_IN_PROGRESS", "Your previous submission is still processing", true],
    [409, "APPROVAL_TERMINAL", "This approval was already decided", false],
    [429, undefined, "Too many requests", true],
    [500, "INTERNAL_ERROR", "Something went wrong", true],
  ] as const)("maps %i %s to a clear message", (status, code, title, retryable) => {
    const description = describeProblem(new ApiProblem(status, code, "server detail"));
    expect(description.title).toBe(title);
    expect(description.retryable).toBe(retryable);
  });

  it("asks only a 401 to sign in again", () => {
    expect(describeProblem(new ApiProblem(401, undefined, undefined)).reauthenticate).toBe(true);
    expect(describeProblem(new ApiProblem(403, "ACCESS_DENIED", undefined)).reauthenticate).toBe(false);
  });

  it("shows the server's safe message for business conflicts", () => {
    const description = describeProblem(
      new ApiProblem(409, "CREDIT_LIMIT_CONFLICT", "Credit limit would be exceeded"),
    );
    expect(description.description).toBe("Credit limit would be exceeded");
  });

  it("treats fetch failures as retryable network errors", () => {
    const problem = toProblem(new TypeError("Failed to fetch"));
    expect(problem.status).toBe(0);
    expect(describeProblem(problem).title).toBe("Cannot reach MaterialFlow");
    expect(isRetryable(new TypeError("Failed to fetch"))).toBe(true);
  });
});
