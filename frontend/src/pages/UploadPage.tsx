import { useId, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError, UnauthorizedError } from "../api/client";
import { createContract } from "../api/contracts";
import { can, useAuth } from "../auth/AuthContext";
import { ErrorMessage } from "../components/ErrorMessage";

export const MAX_TITLE = 300;
export const MAX_VENDOR = 300;
export const MAX_TEXT = 200_000;

export function UploadPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [title, setTitle] = useState("");
  const [vendorName, setVendorName] = useState("");
  const [contractText, setContractText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const titleId = useId();
  const vendorId = useId();
  const textId = useId();

  if (!can(user?.role, "upload")) {
    return (
      <div className="page">
        <h1>Upload contract</h1>
        <p className="empty">
          Your role ({user?.role}) cannot upload contracts. <Link to="/contracts">Back to the contract list</Link>
        </p>
      </div>
    );
  }

  const blank = !title.trim() || !vendorName.trim() || !contractText.trim();

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (blank) return;
    setSubmitting(true);
    setError(null);
    try {
      const created = await createContract({
        title: title.trim(),
        vendorName: vendorName.trim(),
        contractText,
      });
      navigate(`/contracts/${created.id}`);
    } catch (failure) {
      if (failure instanceof UnauthorizedError) return;
      setError(failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page">
      <h1>Upload contract</h1>
      <p className="muted">
        Paste the contract text. After uploading you can start the AI analysis, and a reviewer then decides.
      </p>
      <form onSubmit={onSubmit} aria-label="Upload contract" className="upload-form">
        <label htmlFor={titleId}>Title</label>
        <input
          id={titleId}
          value={title}
          maxLength={MAX_TITLE}
          required
          onChange={(event) => setTitle(event.target.value)}
        />
        <label htmlFor={vendorId}>Vendor</label>
        <input
          id={vendorId}
          value={vendorName}
          maxLength={MAX_VENDOR}
          required
          onChange={(event) => setVendorName(event.target.value)}
        />
        <label htmlFor={textId}>Contract text</label>
        <textarea
          id={textId}
          rows={16}
          value={contractText}
          maxLength={MAX_TEXT}
          required
          aria-describedby={`${textId}-count`}
          onChange={(event) => setContractText(event.target.value)}
        />
        <p className="muted" id={`${textId}-count`}>
          {contractText.length.toLocaleString()} of {MAX_TEXT.toLocaleString()} characters
        </p>
        <div className="button-row">
          <button type="submit" className="button button-primary" disabled={submitting || blank}>
            {submitting ? "Uploading…" : "Upload"}
          </button>
          <Link className="button" to="/contracts">
            Cancel
          </Link>
        </div>
      </form>
      {error ? <ErrorMessage message={error} /> : null}
    </div>
  );
}
