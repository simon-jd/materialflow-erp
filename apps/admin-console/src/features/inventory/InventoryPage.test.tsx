import { screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { API, apiError } from "@/test/fixtures";
import { renderApp } from "@/test/render";
import { server } from "@/test/server";

describe("InventoryPage", () => {
  it("lists stock positions with available quantity", async () => {
    renderApp("/inventory");
    const table = await screen.findByRole("table", { name: "Inventory availability" });
    const cement = within(table).getByRole("row", { name: /CEM-42-5/ });
    expect(cement).toHaveTextContent("1,000");
    expect(cement).toHaveTextContent("960");
    expect(within(table).getByRole("row", { name: /PLY-18-2440/ })).toHaveTextContent("Structural Plywood");
  });

  it("explains a warehouse-scope refusal", async () => {
    server.use(
      http.get(`${API}/api/v1/inventory/availability`, () =>
        HttpResponse.json(apiError("ACCESS_DENIED", "The operation is outside the actor's scope"), {
          status: 403,
        }),
      ),
    );
    renderApp("/inventory");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Your role or warehouse scope does not allow this action",
    );
  });
});
