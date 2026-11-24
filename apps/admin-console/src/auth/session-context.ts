import { createContext, use } from "react";
import type { Session } from "./session";

/** Provided once per signed-in page load by the app shell; tests provide a synthetic session. */
export const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
  const session = use(SessionContext);
  if (session === null) {
    throw new Error("useSession must be used inside <SessionContext>");
  }
  return session;
}
