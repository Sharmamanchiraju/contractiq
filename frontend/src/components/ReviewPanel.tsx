import { useId, useState } from "react";
import { ApiError, UnauthorizedError } from "../api/client";
import { reviewContract } from "../api/contracts";
import type { ContractStatus, ReviewDecision, ReviewResponse } from "../api/types";
import { ErrorMessage } from "./ErrorMessage";

export const MAX_COMMENT_LENGTH = 2000;
const REVIEWABLE: readonly ContractStatus[] = ["ANALYZED", "NEEDS_MANUAL_REVIEW"];

interface Props {
  contractId: number;
  status: ContractStatus;
  onReviewed: (response: ReviewResponse) => void;
  /** Called when the backend refuses because the contract changed; the page reloads it. */
  onConflict: () => void;
}

/**
 * Where a human reviewer approves or rejects. A comment is required for both decisions (the backend
 * only insists on one for a rejection; the UI is stricter on purpose). The buttons are disabled,
 * with the reason written next to them, until the contract can be decided and a comment is typed.
 */
export function ReviewPanel({ contractId, status, onReviewed, onConflict }: Props) {
  const [comment, setComment] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const commentId = useId();
  const hintId = useId();

  const reviewable = REVIEWABLE.includes(status);
  const hasComment = comment.trim().length > 0;
  const disabled = !reviewable || !hasComment || submitting;

  let hint = "Write a comment explaining your decision to enable the buttons.";
  if (!reviewable) {
    hint =
      status === "ANALYZING"
        ? "This contract is being analyzed. You can decide once the analysis has finished."
        : status === "UPLOADED"
          ? "This contract has not been analyzed yet. An uploader needs to start the analysis first."
          : "This contract has already been decided.";
  } else if (hasComment) {
    hint = "Your decision is final and is recorded under your name.";
  }

  async function decide(decision: ReviewDecision) {
    setSubmitting(true);
    setError(null);
    try {
      const response = await reviewContract(contractId, { decision, comment: comment.trim() });
      onReviewed(response);
    } catch (failure) {
      if (failure instanceof UnauthorizedError) return; // the app is already returning to the login page
      const message = failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.";
      setError(message);
      if (failure instanceof ApiError && failure.status === 409) onConflict();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="review" aria-labelledby="review-heading">
      <h2 id="review-heading">Your decision</h2>
      <p className="muted">
        The AI cannot approve or reject contracts. You decide, and your comment is kept with the decision.
      </p>
      <label htmlFor={commentId}>Comment (required)</label>
      <textarea
        id={commentId}
        value={comment}
        maxLength={MAX_COMMENT_LENGTH}
        rows={4}
        disabled={!reviewable || submitting}
        aria-describedby={hintId}
        onChange={(event) => setComment(event.target.value)}
      />
      <p className="muted" id={hintId}>
        {hint} ({comment.length}/{MAX_COMMENT_LENGTH})
      </p>
      <div className="button-row">
        <button type="button" className="button button-approve" disabled={disabled} onClick={() => decide("APPROVED")}>
          Approve
        </button>
        <button type="button" className="button button-reject" disabled={disabled} onClick={() => decide("REJECTED")}>
          Reject
        </button>
      </div>
      {error ? <ErrorMessage message={error} /> : null}
    </section>
  );
}
