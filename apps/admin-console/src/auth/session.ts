import { z } from "zod";

/** Realm roles the console reacts to. The API enforces the same roles on every request. */
export const Role = {
  SalesOrderWrite: "SALES_ORDER_WRITE",
  ApprovalDecide: "APPROVAL_DECIDE",
} as const;
export type Role = (typeof Role)[keyof typeof Role];

export interface Session {
  userId: string;
  organizationId: string;
  displayName: string;
  roles: ReadonlySet<string>;
  /** Sends the browser through the identity provider again (expired or revoked session). */
  signIn: () => void;
  signOut: () => void;
}

// z.guid(): any 8-4-4-4-12 hex id, like java.util.UUID; the synthetic fixtures are not RFC 9562 UUIDs.
const idTokenClaims = z.object({
  user_id: z.guid(),
  organization_id: z.guid(),
  roles: z.array(z.string()).default([]),
  name: z.string().optional(),
  preferred_username: z.string().optional(),
});

export class SessionClaimsError extends Error {
  override readonly name = "SessionClaimsError";
}

/**
 * Identity, organization and roles come only from the ID token the identity provider signed; the
 * console never sends them as request headers. Gating here is a convenience, not a security boundary.
 */
export function sessionFromClaims(claims: unknown, actions: Pick<Session, "signIn" | "signOut">): Session {
  const parsed = idTokenClaims.safeParse(claims);
  if (!parsed.success) {
    throw new SessionClaimsError("The signed-in account has no MaterialFlow organization or user id.");
  }
  const { user_id, organization_id, roles, name, preferred_username } = parsed.data;
  return {
    userId: user_id,
    organizationId: organization_id,
    displayName: name ?? preferred_username ?? user_id,
    roles: new Set(roles),
    ...actions,
  };
}

export function hasRole(session: Session, role: Role): boolean {
  return session.roles.has(role);
}
