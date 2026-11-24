import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { StatusScreen } from "./components/StatusScreen";
import { loadRuntimeConfig, RuntimeConfigError } from "./config/runtime-config";
import "./index.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Missing #root element");
}
const root = createRoot(container);

try {
  const config = loadRuntimeConfig();
  root.render(
    <StrictMode>
      <App config={config} />
    </StrictMode>,
  );
} catch (error) {
  if (!(error instanceof RuntimeConfigError)) {
    throw error;
  }
  root.render(
    <StatusScreen title="The console is not configured">
      <p>{error.message}</p>
    </StatusScreen>,
  );
}
