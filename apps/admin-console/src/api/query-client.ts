import { QueryClient } from "@tanstack/react-query";
import { isRetryable } from "./problem";

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        // 4xx answers are final; only transient failures (network, 429, 5xx) are retried.
        retry: (failureCount, error) => failureCount < 2 && isRetryable(error),
        refetchOnWindowFocus: true,
      },
      mutations: { retry: false },
    },
  });
}
