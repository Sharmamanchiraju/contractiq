import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { ApiError, UnauthorizedError } from "../api/client";
import { analyzeContract, getAnalysis, getContract } from "../api/contracts";
import type { AnalysisResponse, ContractDetail, ReviewResponse } from "../api/types";
import { can, useAuth } from "../auth/AuthContext";
import { AiNotice } from "../components/AiNotice";
import { AnalysisPanel } from "../components/AnalysisPanel";
import { ContractText } from "../components/ContractText";
import { ErrorMessage } from "../components/ErrorMessage";
import { ReviewPanel } from "../components/ReviewPanel";
import { StatusBadge } from "../components/StatusBadge";
import { useContractPolling } from "../hooks/useContractPolling";
import { explainAnalysisError, formatTimestamp } from "../lib/format";
import { buildSegments, locateQuote } from "../lib/highlight";

export function ContractDetailPage() {
  const { id } = useParams();
  const contractId = Number(id);
  if (!Number.isInteger(contractId) || contractId < 1) {
    return (
      <div className="page">
        <h1>Contract not found</h1>
        <p className="empty">
          That is not a valid contract number. <Link to="/contracts">Back to the contract list</Link>
        </p>
      </div>
    );
  }
  return <ContractDetailView contractId={contractId} />;
}

function ContractDetailView({ contractId }: { contractId: number }) {
  const { user } = useAuth();
  const role = user?.role;
  const [contract, setContract] = useState<ContractDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<AnalysisResponse | null>(null);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [selectedFlag, setSelectedFlag] = useState<number | null>(null);
  const [decision, setDecision] = useState<ReviewResponse | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const loadAnalysis = useCallback(
    async (signal?: AbortSignal) => {
      setAnalysisError(null);
      try {
        setAnalysis(await getAnalysis(contractId, signal));
      } catch (failure) {
        if (signal?.aborted || failure instanceof UnauthorizedError) return;
        if (failure instanceof ApiError && failure.status === 404) {
          setAnalysis(null); // no analysis exists yet: not an error
          return;
        }
        setAnalysisError(failure instanceof ApiError ? failure.message : "Could not load the analysis.");
      }
    },
    [contractId],
  );

  // Load the contract (and its analysis, when it has one) when the page opens or is refreshed.
  useEffect(() => {
    const controller = new AbortController();
    setLoadError(null);
    getContract(contractId, controller.signal)
      .then(async (loaded) => {
        setContract(loaded);
        if (loaded.status !== "UPLOADED" && loaded.status !== "ANALYZING") {
          await loadAnalysis(controller.signal);
        }
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted || failure instanceof UnauthorizedError) return;
        setLoadError(failure instanceof ApiError ? failure.message : "Could not load the contract.");
      });
    return () => controller.abort();
  }, [contractId, loadAnalysis, reloadKey]);

  // While the contract is ANALYZING, check its status every 2 seconds until it is done.
  const analyzing = contract?.status === "ANALYZING";
  const poll = useContractPolling(
    contractId,
    analyzing,
    (latest) => {
      setContract(latest);
      if (latest.status !== "ANALYZING") void loadAnalysis();
    },
    reloadKey,
  );

  const located = useMemo(
    () => (analysis && contract ? analysis.result.riskFlags.map((flag) => locateQuote(contract.contractText, flag.quote)) : []),
    [analysis, contract],
  );
  const segments = useMemo(
    () => (contract ? buildSegments(contract.contractText, located) : []),
    [contract, located],
  );

  async function startAnalysis() {
    setStarting(true);
    setActionError(null);
    try {
      await analyzeContract(contractId);
      setAnalysis(null);
      setSelectedFlag(null);
      setDecision(null);
      setContract((current) => (current ? { ...current, status: "ANALYZING", analysisErrorCode: null } : current));
    } catch (failure) {
      if (failure instanceof UnauthorizedError) return;
      setActionError(failure instanceof ApiError ? failure.message : "Could not start the analysis.");
      if (failure instanceof ApiError && failure.status === 409) setReloadKey((key) => key + 1);
    } finally {
      setStarting(false);
    }
  }

  if (loadError) {
    return (
      <div className="page">
        <p>
          <Link to="/contracts">← Back to contracts</Link>
        </p>
        <ErrorMessage message={loadError} onRetry={() => setReloadKey((key) => key + 1)} />
      </div>
    );
  }
  if (!contract) {
    return (
      <div className="page">
        <p role="status" className="muted">
          Loading contract…
        </p>
      </div>
    );
  }

  const status = contract.status;
  const canAnalyze = can(role, "analyze") && (status === "UPLOADED" || status === "ANALYZED" || status === "NEEDS_MANUAL_REVIEW");
  const showAnalysis = analysis !== null && status !== "ANALYZING";

  return (
    <div className="page detail">
      <p>
        <Link to="/contracts">← Back to contracts</Link>
      </p>
      <header className="detail-head">
        <h1>{contract.title}</h1>
        <p>
          {contract.vendorName} · <StatusBadge status={status} /> · uploaded {formatTimestamp(contract.createdAt)}
        </p>
      </header>

      <div className="split">
        {/* The scrollable text box below is the labelled region; labelling this wrapper too would announce it twice. */}
        <section className="pane">
          <h2>Original contract text</h2>
          <ContractText segments={segments} flags={analysis?.result.riskFlags ?? []} selectedFlag={selectedFlag} />
        </section>

        <section className="pane" aria-labelledby="analysis-heading">
          <h2 id="analysis-heading">AI analysis</h2>
          <AiNotice provider={showAnalysis ? analysis.provider : undefined} />

          {status === "UPLOADED" ? <p className="muted">This contract has not been analyzed yet.</p> : null}

          {status === "ANALYZING" ? (
            <div className="progress" role="status" aria-live="polite">
              <span className="spinner" aria-hidden="true" />
              <div>
                <p>
                  <strong>The AI is analyzing this contract.</strong>
                </p>
                <p className="muted">This page checks the status every 2 seconds. You can leave and come back.</p>
                {poll.error ? <p className="muted">Having trouble checking the status: {poll.error}</p> : null}
                {poll.stopped && poll.stopped !== "finished" && poll.stopped !== "unauthorized" ? (
                  <>
                    <p>
                      {poll.stopped === "timeout"
                        ? "The analysis is taking longer than expected, so automatic checking has stopped."
                        : "Automatic checking has stopped because the status could not be read."}
                    </p>
                    <button type="button" className="button" onClick={() => setReloadKey((key) => key + 1)}>
                      Check again
                    </button>
                  </>
                ) : null}
              </div>
            </div>
          ) : null}

          {status === "NEEDS_MANUAL_REVIEW" ? (
            <div className="warning" role="status">
              <p>
                <strong>The AI could not finish this analysis.</strong>
              </p>
              <p>{explainAnalysisError(contract.analysisErrorCode)}</p>
              <p className="muted">Reason code: {contract.analysisErrorCode ?? "none recorded"}</p>
              <p>A person needs to read this contract and decide.</p>
            </div>
          ) : null}

          {status === "NEEDS_MANUAL_REVIEW" && analysis ? (
            <p className="muted">An earlier analysis exists and is shown below. It is not from the latest attempt.</p>
          ) : null}

          {(status === "APPROVED" || status === "REJECTED") && !decision ? (
            <p className="notice" role="status">
              This contract has been {status === "APPROVED" ? "approved" : "rejected"} by a reviewer. It cannot be
              analyzed or decided again.
            </p>
          ) : null}

          {canAnalyze ? (
            <button type="button" className="button button-primary" disabled={starting} onClick={startAnalysis}>
              {starting ? "Starting…" : status === "UPLOADED" ? "Analyze with AI" : "Analyze again"}
            </button>
          ) : null}
          {actionError ? <ErrorMessage message={actionError} /> : null}
          {analysisError ? <ErrorMessage message={analysisError} onRetry={() => void loadAnalysis()} /> : null}

          {showAnalysis ? (
            <AnalysisPanel
              analysis={analysis}
              located={located}
              selectedFlag={selectedFlag}
              onSelectFlag={setSelectedFlag}
            />
          ) : null}
        </section>
      </div>

      {decision ? (
        <section className="notice" role="status" aria-label="Decision recorded">
          <h2>Decision recorded</h2>
          <p>
            You {decision.decision === "APPROVED" ? "approved" : "rejected"} this contract on{" "}
            {formatTimestamp(decision.createdAt)}.
          </p>
          <p>Your comment: {decision.comment}</p>
        </section>
      ) : can(role, "review") ? (
        <ReviewPanel
          contractId={contractId}
          status={status}
          onReviewed={(response) => {
            setDecision(response);
            setContract((current) => (current ? { ...current, status: response.contractStatus } : current));
          }}
          onConflict={() => setReloadKey((key) => key + 1)}
        />
      ) : (
        <p className="muted">Approving or rejecting is done by reviewers. Your role ({role}) can read this contract.</p>
      )}
    </div>
  );
}
