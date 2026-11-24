import { describe, expect, it, vi } from "vitest";
import { ORGANIZATION_ID, SALES_USER_ID } from "@/test/fixtures";
import { returnToFrom } from "./oidc";
import { hasRole, Role, SessionClaimsError, sessionFromClaims } from "./session";

const actions = { signIn: vi.fn(), signOut: vi.fn() };

describe("sessionFromClaims", () => {
  it("takes identity, organization and roles from the ID token claims", () => {
    const session = sessionFromClaims(
      {
        user_id: SALES_USER_ID,
        organization_id: ORGANIZATION_ID,
        roles: ["SALES_ORDER_WRITE", "default-roles-materialflow"],
        name: "Synthetic Sales",
      },
      actions,
    );
    expect(session).toMatchObject({
      userId: SALES_USER_ID,
      organizationId: ORGANIZATION_ID,
      displayName: "Synthetic Sales",
    });
    expect(hasRole(session, Role.SalesOrderWrite)).toBe(true);
    expect(hasRole(session, Role.ApprovalDecide)).toBe(false);
  });

  it("treats a missing roles claim as a read-only user", () => {
    const session = sessionFromClaims({ user_id: SALES_USER_ID, organization_id: ORGANIZATION_ID }, actions);
    expect(session.roles.size).toBe(0);
    expect(session.displayName).toBe(SALES_USER_ID);
  });

  it("refuses accounts without a MaterialFlow organization", () => {
    expect(() => sessionFromClaims({ user_id: SALES_USER_ID }, actions)).toThrow(SessionClaimsError);
  });
});

describe("returnToFrom", () => {
  it("returns only same-origin paths from the sign-in state", () => {
    expect(returnToFrom({ returnTo: "/orders?status=RESERVED" })).toBe("/orders?status=RESERVED");
    expect(returnToFrom({ returnTo: "//evil.example.test/" })).toBe("/orders");
    expect(returnToFrom({ returnTo: "https://evil.example.test/" })).toBe("/orders");
    expect(returnToFrom({ returnTo: "/auth/callback?code=1" })).toBe("/orders");
    expect(returnToFrom(undefined)).toBe("/orders");
  });
});
