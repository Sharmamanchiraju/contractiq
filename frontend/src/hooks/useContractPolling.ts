import { useEffect, useRef, useState } from "react";
import { UnauthorizedError } from "../api/client";
import { getContract } from "../api/contracts";
import type { ContractDetail } from "../api/types";

export const POLL_INTERVAL_MS = 2_000;
export const MAX_CONSECUTIVE_ERRORS = 5;
export const MAX_POLL_DURATION_MS = 5 * 60 * 1_000;

export type PollStopReason = "finished" | "errors" | "timeout" | "unauthorized";

export interface PollState {
  /** Why polling stopped, or null while it is running or has not started. */
  stopped: PollStopReason | null;
  /** The most recent failure while polling, if any (cleared by the next good answer). */
  error: string | null;
}

/**
 * While `active` is true, asks the backend for the contract every 2 seconds and passes each answer
 * to `onContract`. It stops when the status leaves ANALYZING ("finished"), after 5 failures in a row
 * ("errors"), after 5 minutes ("timeout"), on a 401 ("unauthorized"), when `active` turns false, and
 * when the component goes away. Only one request is ever in flight: the next poll is scheduled after
 * the previous one answers.
 */
export function useContractPolling(
  contractId: number,
  active: boolean,
  onContract: (contract: ContractDetail) => void,
  /** Change this number to start polling again after it has stopped (for a "Check again" button). */
  restartKey = 0,
): PollState {
  const [state, setState] = useState<PollState>({ stopped: null, error: null });
  const onContractRef = useRef(onContract);
  onContractRef.current = onContract;

  useEffect(() => {
    if (!active) return;
    setState({ stopped: null, error: null });
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let consecutiveErrors = 0;
    const startedAt = Date.now();
    const controller = new AbortController();

    const stop = (reason: PollStopReason) => {
      setState((previous) => ({ stopped: reason, error: reason === "finished" ? null : previous.error }));
    };

    const poll = async () => {
      try {
        const contract = await getContract(contractId, controller.signal);
        if (cancelled) return;
        consecutiveErrors = 0;
        setState({ stopped: null, error: null });
        onContractRef.current(contract);
        if (contract.status !== "ANALYZING") {
          stop("finished");
          return;
        }
      } catch (error) {
        if (cancelled) return;
        if (error instanceof UnauthorizedError) {
          stop("unauthorized");
          return;
        }
        consecutiveErrors += 1;
        const message = error instanceof Error ? error.message : "Could not check the status.";
        setState({ stopped: null, error: message });
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
          stop("errors");
          return;
        }
      }
      if (Date.now() - startedAt >= MAX_POLL_DURATION_MS) {
        stop("timeout");
        return;
      }
      timer = setTimeout(poll, POLL_INTERVAL_MS);
    };

    timer = setTimeout(poll, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [contractId, active, restartKey]);

  return state;
}
