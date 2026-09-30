# CNCF AI Audit integration for textus-ai-runtime

The `textus-ai-runtime` component in textus-ai-core is the primary Textus AI execution consumer of CNCF's generic AI Audit capability (CNCF Phase 97).

Responsibility split:

- `textus-ai-runtime`: AI execution, provider/model invocation, runtime request/response handling.
- CNCF AI Audit: provider-neutral AI Interaction identity, execution-context correlation, audit/evidence contract and policy hooks.
- CNCF built-in Service Bus/Journal: authoritative selected interaction/evidence persistence.
- OpenTelemetry: observational telemetry, not the authoritative audit record.
- Workflow/Continuation/JudgmentAction: additional AI execution contexts that use the same CNCF Audit contract.
- cbd-support / Control Center: projections, KPI analysis and Human-in-the-Loop review.

The runtime should supply both logical AI context and the actual provider wire request/response when policy permits. Later correction, retry/escalation, Admission/review and downstream outcomes can be attached as evaluation evidence without making textus-ai-runtime own those domain semantics.

The evidence is intended for operational audit and for engineering feedback: deviation/runaway detection, prompt/context/guard tuning, provider/routing improvement, and identification of stable AI work that can be converted into deterministic Workflow/rules/programs.

Raw payload persistence must follow CNCF classification, redaction/reference, authorization and retention policy.
