import { Check, X } from "lucide-react";
import { useId, useRef, useState } from "react";
import { ProblemAlert } from "@/components/ProblemAlert";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useSubmissionKey } from "@/lib/use-submission-key";
import { useDecideApproval, type DecideApprovalVariables } from "./approval-queries";

const MAX_REASON = 500;

/**
 * Approve / reject for one approval. Callers render it only when the server marked the approval as
 * decidable by this user; the decision endpoint re-checks role, approver set and maker-checker.
 */
export function ApprovalActions({ approvalId, orderNumber }: { approvalId: string; orderNumber: string }) {
  const decide = useDecideApproval();
  const submission = useSubmissionKey();
  const dialog = useRef<HTMLDialogElement>(null);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const reasonId = useId();

  const submit = (decision: DecideApprovalVariables["decision"], text?: string) => {
    const request = { approvalId, decision, ...(text ? { reason: text } : {}) };
    decide.mutate(
      { ...request, idempotencyKey: submission.keyFor(request) },
      {
        onSuccess: () => {
          submission.reset();
          dialog.current?.close();
        },
      },
    );
  };

  const confirmReject = () => {
    const text = reason.trim();
    if (text.length === 0) {
      setReasonError("A reason is required to reject an order");
      return;
    }
    if (text.length > MAX_REASON) {
      setReasonError(`Keep the reason under ${MAX_REASON} characters`);
      return;
    }
    submit("REJECT", text);
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={decide.isPending}
          onClick={() => {
            submit("APPROVE");
          }}
        >
          <Check aria-hidden />
          Approve
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={decide.isPending}
          onClick={() => {
            setReasonError(null);
            dialog.current?.showModal();
          }}
        >
          <X aria-hidden />
          Reject
        </Button>
      </div>
      {decide.isError && decide.variables.decision === "APPROVE" && <ProblemAlert error={decide.error} />}
      <dialog
        ref={dialog}
        aria-labelledby={`${reasonId}-title`}
        className="m-auto w-full max-w-md rounded-lg border bg-card p-5 text-card-foreground shadow-lg backdrop:bg-black/40"
      >
        <form
          method="dialog"
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            confirmReject();
          }}
        >
          <h2 id={`${reasonId}-title`} className="text-base font-semibold">
            Reject order {orderNumber}
          </h2>
          <div className="flex flex-col gap-2">
            <Label htmlFor={reasonId}>Reason</Label>
            <Textarea
              id={reasonId}
              value={reason}
              maxLength={MAX_REASON}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={reasonError ? `${reasonId}-error` : undefined}
              onChange={(event) => {
                setReason(event.target.value);
              }}
            />
            {reasonError && (
              <p id={`${reasonId}-error`} className="text-sm text-destructive">
                {reasonError}
              </p>
            )}
          </div>
          {decide.isError && decide.variables.decision === "REJECT" && <ProblemAlert error={decide.error} />}
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => {
                dialog.current?.close();
              }}
            >
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={decide.isPending}>
              Reject order
            </Button>
          </div>
        </form>
      </dialog>
    </div>
  );
}
