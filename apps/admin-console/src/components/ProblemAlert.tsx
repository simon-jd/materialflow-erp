import { AlertCircle } from "lucide-react";
import { describeProblem } from "@/api/problem";
import { useSession } from "@/auth/session-context";
import { Alert, AlertDescription, AlertTitle } from "./ui/alert";
import { Button } from "./ui/button";

interface ProblemAlertProps {
  error: unknown;
  onRetry?: () => void;
}

/** Turns any API failure into a readable message with the next step the user can take. */
export function ProblemAlert({ error, onRetry }: ProblemAlertProps) {
  const session = useSession();
  const problem = describeProblem(error);
  const fields = Object.entries(problem.fieldErrors);
  return (
    <Alert variant="destructive">
      <AlertCircle aria-hidden />
      <AlertTitle>{problem.title}</AlertTitle>
      <AlertDescription>
        <p>{problem.description}</p>
        {fields.length > 0 && (
          <ul className="list-disc pl-5">
            {fields.map(([field, message]) => (
              <li key={field}>
                <span className="font-mono">{field}</span> {message}
              </li>
            ))}
          </ul>
        )}
        {problem.traceId && <p className="text-xs">Reference: {problem.traceId}</p>}
        {(problem.reauthenticate || (problem.retryable && onRetry)) && (
          <div className="mt-2 flex gap-2">
            {problem.reauthenticate && (
              <Button size="sm" onClick={session.signIn}>
                Sign in again
              </Button>
            )}
            {problem.retryable && onRetry && (
              <Button size="sm" variant="outline" onClick={onRetry}>
                Try again
              </Button>
            )}
          </div>
        )}
      </AlertDescription>
    </Alert>
  );
}
