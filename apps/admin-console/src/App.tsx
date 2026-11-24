import { QueryClientProvider } from "@tanstack/react-query";
import type { UserManager } from "oidc-client-ts";
import { useCallback, useMemo, useState } from "react";
import { AuthProvider, useAuth, useAutoSignin } from "react-oidc-context";
import { createBrowserRouter } from "react-router";
import { RouterProvider } from "react-router/dom";
import { ApiClientContext } from "./api/api-client-context";
import { createApiClient } from "./api/client";
import { createQueryClient } from "./api/query-client";
import { createUserManager, returnToFrom } from "./auth/oidc";
import { sessionFromClaims, SessionClaimsError } from "./auth/session";
import { SessionContext } from "./auth/session-context";
import { StatusScreen } from "./components/StatusScreen";
import { Button } from "./components/ui/button";
import type { RuntimeConfig } from "./config/runtime-config";
import { routes } from "./routes";

export function App({ config }: { config: RuntimeConfig }) {
  const [userManager] = useState(() => createUserManager(config));
  return (
    <AuthProvider
      userManager={userManager}
      onSigninCallback={(user) => {
        // Drop ?code=&state= from the address bar before the router reads it.
        window.history.replaceState({}, document.title, returnToFrom(user?.state));
      }}
    >
      <SignedIn config={config} userManager={userManager} />
    </AuthProvider>
  );
}

function SignedIn({ config, userManager }: { config: RuntimeConfig; userManager: UserManager }) {
  const auth = useAuth();
  const { isLoading, isAuthenticated, error } = useAutoSignin({
    signinArgs: { state: { returnTo: `${window.location.pathname}${window.location.search}` } },
  });
  const signIn = useCallback(() => void auth.signinRedirect(), [auth]);
  const signOut = useCallback(() => void auth.signoutRedirect(), [auth]);

  if (error) {
    return (
      <StatusScreen title="Sign-in failed">
        <p>{error.message}</p>
        <Button onClick={signIn}>Try again</Button>
      </StatusScreen>
    );
  }
  if (isLoading || !isAuthenticated || !auth.user) {
    return <StatusScreen title="Signing you in…" />;
  }
  return (
    <ConsoleShell
      config={config}
      userManager={userManager}
      claims={auth.user.profile}
      signIn={signIn}
      signOut={signOut}
    />
  );
}

interface ConsoleShellProps {
  config: RuntimeConfig;
  userManager: UserManager;
  claims: unknown;
  signIn: () => void;
  signOut: () => void;
}

function ConsoleShell({ config, userManager, claims, signIn, signOut }: ConsoleShellProps) {
  // Created once per signed-in page load, after the OIDC callback URL has been cleaned up.
  const [router] = useState(() => createBrowserRouter(routes));
  const [queryClient] = useState(createQueryClient);
  const [api] = useState(() =>
    createApiClient({
      baseUrl: config.apiOrigin,
      getAccessToken: async () => (await userManager.getUser())?.access_token,
    }),
  );
  const session = useMemo(() => {
    try {
      return sessionFromClaims(claims, { signIn, signOut });
    } catch (error) {
      if (error instanceof SessionClaimsError) return error;
      throw error;
    }
  }, [claims, signIn, signOut]);

  if (session instanceof SessionClaimsError) {
    return (
      <StatusScreen title="Account not provisioned">
        <p>{session.message} Ask an administrator to assign you to an organization.</p>
        <Button variant="outline" onClick={signOut}>
          Sign out
        </Button>
      </StatusScreen>
    );
  }
  return (
    <SessionContext value={session}>
      <ApiClientContext value={api}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ApiClientContext>
    </SessionContext>
  );
}
