import createClient, { type Client, type Middleware } from "openapi-fetch";
import type { components, paths } from "./generated/schema";
import { toApiProblem } from "./problem";

export type ApiClient = Client<paths>;
export type Schemas = components["schemas"];

export interface ApiClientOptions {
  baseUrl: string;
  getAccessToken: () => Promise<string | undefined>;
  fetch?: typeof globalThis.fetch;
}

/** Typed client generated from the OpenAPI contract. The only identity it sends is the bearer token. */
export function createApiClient({ baseUrl, getAccessToken, fetch }: ApiClientOptions): ApiClient {
  const client = createClient<paths>({ baseUrl, ...(fetch ? { fetch } : {}) });
  const bearer: Middleware = {
    async onRequest({ request }) {
      const token = await getAccessToken();
      if (token) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      return request;
    },
  };
  client.use(bearer);
  return client;
}

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Returns the success body or throws an {@link ApiProblem} describing the failure. */
export function unwrap<T>({ data, error, response }: FetchResult<T>): T {
  if (!response.ok || data === undefined) {
    throw toApiProblem(response.status, error);
  }
  return data;
}
