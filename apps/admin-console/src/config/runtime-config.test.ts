import { describe, expect, it } from "vitest";
import { loadRuntimeConfig, RuntimeConfigError } from "./runtime-config";

const valid = {
  apiBaseUrl: "",
  oidcAuthority: "https://id.example.test/realms/materialflow",
  oidcClientId: "materialflow-admin-console",
};

describe("loadRuntimeConfig", () => {
  it("uses the page origin when the API is served from the same origin", () => {
    expect(loadRuntimeConfig(valid).apiOrigin).toBe(window.location.origin);
  });

  it("keeps an explicit API origin without a trailing slash", () => {
    expect(loadRuntimeConfig({ ...valid, apiBaseUrl: "http://localhost:8081/" }).apiOrigin).toBe(
      "http://localhost:8081",
    );
  });

  it.each([
    [undefined],
    [{ ...valid, oidcAuthority: "not a url" }],
    [{ ...valid, oidcClientId: "" }],
    [{ ...valid, apiBaseUrl: "javascript:alert(1)" }],
  ])("rejects missing or invalid values (%#)", (config) => {
    expect(() => loadRuntimeConfig(config)).toThrow(RuntimeConfigError);
  });
});
