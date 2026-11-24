/**
 * Normalizes every failure the console can see into one shape: the API's stable error body
 * (`errorCode`, `message`, `fieldErrors`, `traceId`), an RFC 7807 problem document (`title`,
 * `detail`, `status`), the gateway's own JSON errors, empty-bodied 401/429 responses and
 * network failures.
 */
export class ApiProblem extends Error {
  override readonly name = "ApiProblem";

  constructor(
    readonly status: number,
    readonly code: string | undefined,
    readonly detail: string | undefined,
    readonly fieldErrors: Readonly<Record<string, string>> = {},
    readonly traceId?: string,
  ) {
    super(`${status === 0 ? "Network error" : `HTTP ${status}`}${code ? ` ${code}` : ""}`);
  }
}

export const NETWORK_ERROR_STATUS = 0;

function stringField(body: Record<string, unknown>, ...names: string[]): string | undefined {
  for (const name of names) {
    const value = body[name];
    if (typeof value === "string" && value.length > 0) {
      return value;
    }
  }
  return undefined;
}

export function toApiProblem(status: number, body: unknown): ApiProblem {
  if (typeof body !== "object" || body === null) {
    return new ApiProblem(status, undefined, undefined);
  }
  const record = body as Record<string, unknown>;
  const fieldErrors: Record<string, string> = {};
  const fields = record.fieldErrors ?? record.errors;
  if (typeof fields === "object" && fields !== null && !Array.isArray(fields)) {
    for (const [field, message] of Object.entries(fields)) {
      if (typeof message === "string") {
        fieldErrors[field] = message;
      }
    }
  }
  return new ApiProblem(
    status,
    stringField(record, "errorCode", "error_code", "code"),
    stringField(record, "message", "detail", "title"),
    fieldErrors,
    stringField(record, "traceId", "trace_id", "instance"),
  );
}

export function toProblem(error: unknown): ApiProblem {
  if (error instanceof ApiProblem) {
    return error;
  }
  // fetch rejects with a TypeError when the network, DNS, TLS or CORS layer fails.
  return new ApiProblem(
    NETWORK_ERROR_STATUS,
    "NETWORK_ERROR",
    error instanceof Error ? error.message : undefined,
  );
}

export interface ProblemDescription {
  title: string;
  description: string;
  fieldErrors: Readonly<Record<string, string>>;
  traceId?: string;
  /** The same request may succeed later without changes (safe to offer "Try again"). */
  retryable: boolean;
  /** The session is no longer valid; the user must sign in again. */
  reauthenticate: boolean;
}

const CODE_MESSAGES: Record<string, { title: string; description: string }> = {
  MAKER_CHECKER_VIOLATION: {
    title: "You cannot decide your own order",
    description: "Maker-checker rules require a different approver for orders you submitted.",
  },
  NOT_ASSIGNED_APPROVER: {
    title: "You are not an approver for this order",
    description: "Only the approvers frozen on this approval when it was created can decide it.",
  },
  IDEMPOTENCY_IN_PROGRESS: {
    title: "Your previous submission is still processing",
    description: "Wait a moment and try again. Retrying is safe: it will not create a duplicate.",
  },
  IDEMPOTENCY_KEY_REUSED: {
    title: "This submission changed after it was sent",
    description: "Submit the form again to send the updated values as a new request.",
  },
  APPROVAL_TERMINAL: {
    title: "This approval was already decided",
    description: "Someone else decided it first. The latest state is shown below.",
  },
  SHARED_RATE_LIMITER_UNAVAILABLE: {
    title: "Changes are temporarily paused",
    description: "Write protection is unavailable, so the gateway is refusing changes. Try again shortly.",
  },
};

function base(problem: ApiProblem): Omit<ProblemDescription, "fieldErrors" | "traceId"> {
  const known = problem.code === undefined ? undefined : CODE_MESSAGES[problem.code];
  const retryable =
    problem.status === NETWORK_ERROR_STATUS ||
    problem.status === 429 ||
    problem.status >= 500 ||
    problem.code === "IDEMPOTENCY_IN_PROGRESS";
  if (known) {
    return { ...known, retryable, reauthenticate: false };
  }
  switch (problem.status) {
    case NETWORK_ERROR_STATUS:
      return {
        title: "Cannot reach MaterialFlow",
        description: "Check your connection and try again.",
        retryable,
        reauthenticate: false,
      };
    case 400:
      return {
        title: "Some values were rejected",
        description:
          problem.code === "VALIDATION_FAILED"
            ? "Correct the highlighted fields and submit again."
            : (problem.detail ?? "The request was not valid."),
        retryable,
        reauthenticate: false,
      };
    case 401:
      return {
        title: "Your session has expired",
        description: "Sign in again to continue. Nothing you submitted was lost on the server.",
        retryable: false,
        reauthenticate: true,
      };
    case 403:
      return {
        title: "You do not have permission to do this",
        description:
          "Your role or warehouse scope does not allow this action. Ask an administrator for access.",
        retryable,
        reauthenticate: false,
      };
    case 404:
      return {
        title: "Not found",
        description: "It does not exist or belongs to another organization.",
        retryable,
        reauthenticate: false,
      };
    case 409:
      return {
        title: "This conflicts with the current state",
        description: problem.detail ?? "Refresh to see the latest data, then try again.",
        retryable,
        reauthenticate: false,
      };
    case 429:
      return {
        title: "Too many requests",
        description: "Wait a few seconds and try again.",
        retryable,
        reauthenticate: false,
      };
    default:
      return {
        title: "Something went wrong",
        description:
          "The server could not complete the request. Try again; if it persists, share the reference.",
        retryable,
        reauthenticate: false,
      };
  }
}

export function describeProblem(error: unknown): ProblemDescription {
  const problem = toProblem(error);
  return {
    ...base(problem),
    fieldErrors: problem.fieldErrors,
    ...(problem.traceId === undefined ? {} : { traceId: problem.traceId }),
  };
}

export function isRetryable(error: unknown): boolean {
  return describeProblem(error).retryable;
}
