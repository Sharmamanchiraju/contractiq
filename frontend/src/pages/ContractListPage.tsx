import { useCallback, useEffect, useId, useState } from "react";
import { Link } from "react-router";
import { ApiError, UnauthorizedError } from "../api/client";
import { listContracts } from "../api/contracts";
import { STATUSES, type ContractStatus, type ContractSummary, type PageResponse } from "../api/types";
import { can, useAuth } from "../auth/AuthContext";
import { ErrorMessage } from "../components/ErrorMessage";
import { StatusBadge } from "../components/StatusBadge";
import { STATUS_INFO, formatTimestamp } from "../lib/format";

const PAGE_SIZE = 20;

export function ContractListPage() {
  const { user } = useAuth();
  const [status, setStatus] = useState<ContractStatus | "">("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<ContractSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const filterId = useId();

  const load = useCallback(
    (signal?: AbortSignal) => {
      setLoading(true);
      setError(null);
      listContracts({ status, page, size: PAGE_SIZE }, signal)
        .then((result) => {
          setData(result);
          setLoading(false);
        })
        .catch((failure: unknown) => {
          if (signal?.aborted || failure instanceof UnauthorizedError) return;
          setError(failure instanceof ApiError ? failure.message : "Could not load the contracts.");
          setLoading(false);
        });
    },
    [status, page],
  );

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const items = data?.items ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <div className="page">
      <div className="page-head">
        <h1>Contracts</h1>
        {can(user?.role, "upload") ? (
          <Link className="button button-primary" to="/contracts/new">
            Upload contract
          </Link>
        ) : null}
      </div>

      <div className="filter">
        <label htmlFor={filterId}>Status</label>
        <select
          id={filterId}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as ContractStatus | "");
            setPage(0);
          }}
        >
          <option value="">All statuses</option>
          {STATUSES.map((value) => (
            <option key={value} value={value}>
              {STATUS_INFO[value].label}
            </option>
          ))}
        </select>
      </div>

      {error ? <ErrorMessage message={error} onRetry={() => load()} /> : null}

      {loading && !error ? (
        <p role="status" className="muted">
          Loading contracts…
        </p>
      ) : null}

      {!loading && !error && items.length === 0 ? (
        <p className="empty" role="status">
          {status
            ? `No contracts have the status “${STATUS_INFO[status].label}”.`
            : "There are no contracts yet."}
          {!status && can(user?.role, "upload") ? " Upload the first one to get started." : ""}
        </p>
      ) : null}

      {!error && items.length > 0 ? (
        <>
          <table className="contracts">
            <caption className="visually-hidden">Contracts, newest first</caption>
            <thead>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Vendor</th>
                <th scope="col">Status</th>
                <th scope="col">Uploaded</th>
              </tr>
            </thead>
            <tbody>
              {items.map((contract) => (
                <tr key={contract.id}>
                  <th scope="row">
                    <Link to={`/contracts/${contract.id}`}>{contract.title}</Link>
                  </th>
                  <td>{contract.vendorName}</td>
                  <td>
                    <StatusBadge status={contract.status} />
                  </td>
                  <td>{formatTimestamp(contract.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <nav className="pager" aria-label="Pages">
            <button type="button" className="button" disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>
              Previous
            </button>
            <span>
              Page {page + 1} of {Math.max(totalPages, 1)} ({data?.totalElements ?? 0} contracts)
            </span>
            <button
              type="button"
              className="button"
              disabled={page + 1 >= totalPages || loading}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </nav>
        </>
      ) : null}
    </div>
  );
}
