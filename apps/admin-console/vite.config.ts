/// <reference types="vitest/config" />
import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

/**
 * Production images and static hosting supply `/config.js` per environment. In development the
 * same file is served from env vars, so `dist/` never contains environment-specific values.
 */
function devRuntimeConfig(): Plugin {
  const config = {
    apiBaseUrl: process.env.MATERIALFLOW_API_BASE_URL ?? "http://localhost:8081",
    oidcAuthority: process.env.MATERIALFLOW_OIDC_AUTHORITY ?? "http://localhost:8180/realms/materialflow",
    oidcClientId: process.env.MATERIALFLOW_OIDC_CLIENT_ID ?? "materialflow-admin-console",
  };
  return {
    name: "materialflow-dev-runtime-config",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/config.js", (_request, response) => {
        response.setHeader("Content-Type", "text/javascript; charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
        response.end(`window.__MATERIALFLOW_CONFIG__ = ${JSON.stringify(config)};\n`);
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), devRuntimeConfig()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: { port: 5173, strictPort: true },
  preview: { port: 4173, strictPort: true },
  build: {
    target: "es2022",
    rolldownOptions: {
      output: {
        // Long-lived vendor chunks keep cached across application releases.
        codeSplitting: {
          groups: [
            { name: "react", test: /node_modules[\\/](react|react-dom|scheduler|react-router)[\\/]/ },
            { name: "vendor", test: /node_modules[\\/]/ },
          ],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    restoreMocks: true,
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/api/generated/**", "src/test/**", "src/main.tsx", "src/**/*.test.{ts,tsx}"],
      reporter: ["text-summary", "html", "lcov"],
      thresholds: { lines: 70, functions: 70, branches: 60, statements: 70 },
    },
  },
});
