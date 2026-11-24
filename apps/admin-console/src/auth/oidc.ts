import { UserManager, WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";
import type { RuntimeConfig } from "@/config/runtime-config";

export const CALLBACK_PATH = "/auth/callback";

export interface SignInState {
  returnTo: string;
}

/**
 * Authorization Code + PKCE (oidc-client-ts always uses S256 for the code flow). Tokens live in
 * sessionStorage, scoped to this tab, and are renewed with the refresh token, so no hidden iframe
 * (and no `frame-src` CSP exception) is needed.
 */
export function createUserManager(config: RuntimeConfig, origin = window.location.origin): UserManager {
  const settings: UserManagerSettings = {
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: "code",
    scope: "openid profile",
    automaticSilentRenew: true,
    monitorSession: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  };
  return new UserManager(settings);
}

export function returnToFrom(state: unknown): string {
  if (typeof state === "object" && state !== null && "returnTo" in state) {
    const { returnTo } = state as SignInState;
    // Only same-origin, absolute paths: never redirect to a URL smuggled through the state parameter.
    if (returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.startsWith(CALLBACK_PATH)) {
      return returnTo;
    }
  }
  return "/orders";
}
