# CNCF AI Audit integration for textus-ai-runtime

The `textus-ai-runtime` component in textus-ai-core is the primary Textus AI execution consumer of CNCF's generic AI Audit capability (CNCF Phase 97).

Responsibility split:

- `textus-ai-runtime`: AI execution, provider/model invocation, runtime request/response handling.
- CNCF AI Audit: provider-neutral AI Interaction identity, execution-context correlation, audit/evidence contract and policy hooks.
- CNCF built-in Service Bus/Journal: authoritative selected interaction/evidence persistence.
- CNCF AI Audit Observability Projection: compact/sanitized projection to OpenTelemetry with AIInteractionId/auditRef back pointer.
- OpenTelemetry: operational traces/metrics/logs, not the authoritative audit record.
- Workflow/Continuation/JudgmentAction: additional AI execution contexts that use the same CNCF Audit contract.
- cbd-support / Control Center: projections, KPI analysis and Human-in-the-Loop review.

The runtime should supply both logical AI context and the actual provider wire request/response to AI Audit when policy permits. It should not independently duplicate detailed request/response payloads into OpenTelemetry. AI Audit derives the operational projection and exports only the necessary compact information; OTel retains auditRef so an operator can navigate back to the authoritative interaction record.

Later correction, retry/escalation, Admission/review and downstream outcomes can be attached as evaluation evidence without making textus-ai-runtime own those domain semantics.

The evidence is intended for operational audit and for engineering feedback: deviation/runaway detection, prompt/context/guard tuning, provider/routing improvement, and identification of stable AI work that can be converted into deterministic Workflow/rules/programs.

For quality feedback, agent identity must be separable from provider/model identity. A Dot, OpenClaw, Codex or future agent may invoke or embody different model execution paths; evaluation should therefore support Agent x Provider/Model x Work Type analysis rather than collapsing everything into a provider name. Runtime facts such as latency, usage and cost combine with later Workflow facts such as deterministic validation, Admission, human correction and downstream outcome through the shared AIInteraction/evidence correlation.

Raw payload persistence must follow CNCF classification, redaction/reference, authorization and retention policy.

## Experiment transparency

textus-ai-runtime is not Experiment-aware. If execution occurs under a Textus Experiment run/arm, CNCF ExecutionContext carries the Experiment correlation and CNCF AI Audit captures it automatically. No experimentId/runId/armId is added as a special AI request parameter and provider execution behavior does not change merely because the call belongs to an Experiment.

Experiment-specific recording remains owned by Textus Experiment. When an Experiment Observation represents an AI-backed execution, it may reference the resulting AIInteractionId/evidenceRef. Detailed AI context/request/response remains in AI Audit. Additional safe execution facts useful to comparisons may be added to the ordinary AI Audit/observation contract when generally useful; they are not an Experiment-only execution path.

## CNCF Phase 98 online Experiment routing

CNCF Phase 98 may route a logical Operation through an online Experiment Arm before textus-ai-runtime is invoked. This does not add an Experiment responsibility to Textus AI. The runtime receives the effective admitted AI execution request through the normal path.

When the selected Arm is AI-backed, Phase 97 AI Audit captures Experiment/Run/Arm correlation inherited from ExecutionContext. No online-assignment or Arm-selection logic belongs in textus-ai-runtime.
