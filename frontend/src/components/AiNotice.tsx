/** Says plainly that the analysis was written by an AI and that a person makes the decision. */
export function AiNotice({ provider }: { provider?: string }) {
  return (
    <aside className="ai-notice" role="note" aria-label="About this analysis">
      <strong>AI-generated draft.</strong> This analysis was produced by software
      {provider ? <> (analyzer: {provider})</> : null} and can be wrong or incomplete. It does not approve or
      reject anything: <strong>a human reviewer makes the decision.</strong> Check each risk flag against the
      quoted text in the contract.
    </aside>
  );
}
