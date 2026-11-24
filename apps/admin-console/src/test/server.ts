import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { API, customer, materials, page, stock, warehouse } from "./fixtures";

/** Reference data every screen may load; tests add the handlers for the resource under test. */
export const server = setupServer(
  http.get(`${API}/api/v1/customers`, () => HttpResponse.json(page([customer]))),
  http.get(`${API}/api/v1/materials`, () => HttpResponse.json(page(materials))),
  http.get(`${API}/api/v1/warehouses`, () => HttpResponse.json(page([warehouse]))),
  http.get(`${API}/api/v1/inventory/availability`, () => HttpResponse.json(page(stock))),
);
