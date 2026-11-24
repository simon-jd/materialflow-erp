import { z } from "zod";

declare global {
  interface Window {
    __MATERIALFLOW_CONFIG__?: unknown;
  }
}

const runtimeConfigSchema = z.object({
  /** Origin that serves `/api/v1/...`. Empty means same origin (CloudFront or the nginx proxy). */
  apiBaseUrl: z.union([z.literal(""), z.url({ protocol: /^https?$/ })]),
  oidcAuthority: z.url({ protocol: /^https?$/ }),
  oidcClientId: z.string().min(1),
});

export type RuntimeConfig = z.infer<typeof runtimeConfigSchema> & { apiOrigin: string };

export class RuntimeConfigError extends Error {
  override readonly name = "RuntimeConfigError";
}

/** Reads the per-environment values that `/config.js` puts on `window` before the bundle runs. */
export function loadRuntimeConfig(source: unknown = window.__MATERIALFLOW_CONFIG__): RuntimeConfig {
  const parsed = runtimeConfigSchema.safeParse(source);
  if (!parsed.success) {
    const fields = parsed.error.issues.map((issue) => issue.path.join(".") || "config").join(", ");
    throw new RuntimeConfigError(`Invalid or missing /config.js values: ${fields}`);
  }
  const apiBaseUrl = parsed.data.apiBaseUrl.replace(/\/+$/, "");
  return { ...parsed.data, apiBaseUrl, apiOrigin: apiBaseUrl === "" ? window.location.origin : apiBaseUrl };
}
